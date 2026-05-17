package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.NutritionFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.exception.PreferenceFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.exception.ProvisionsFeedbackBridgeUnavailableException;
import com.example.mealprep.feedback.exception.RecipeFeedbackHandlerUnavailableException;
import com.example.mealprep.feedback.spi.Destination;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real {@link FeedbackRouter} (per ticket 01d). Wired as a {@code @Bean} in {@link
 * com.example.mealprep.feedback.config.FeedbackRouterConfiguration} so 01c's Noop defers via
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Transaction strategy (round-7/8 retro): {@code routeAll} itself is not transactional. Each
 * per-destination dispatch + per-destination routing-log write runs in its own {@code REQUIRES_NEW}
 * tx via the injected template so one destination's failure cannot roll back its peers.
 * Reconciliation of the entry's submission status is a separate {@code REQUIRES_NEW} tx. The {@link
 * FeedbackProcessedEvent} is published INSIDE the reconcile tx so
 * {@code @TransactionalEventListener(AFTER_COMMIT)} listeners fire (events published outside any tx
 * are silently dropped).
 */
public class FeedbackRouterImpl implements FeedbackRouter {

  private static final Logger log = LoggerFactory.getLogger(FeedbackRouterImpl.class);

  /** Truncate width for {@code action_taken} / {@code failure_message} per LLD line 118. */
  static final int MESSAGE_MAX_LEN = 512;

  /** Substrings stripped defensively from failure messages to avoid leaking a key. */
  private static final String[] SECRET_PATTERNS = {"Authorization", "x-api-key", "api_key"};

  private final FeedbackEntryRepository entryRepository;
  private final RoutingLogRepository routingLogRepository;
  private final DestinationDispatcherRegistry registry;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate requiresNewTxTemplate;
  private final Clock clock;

  public FeedbackRouterImpl(
      FeedbackEntryRepository entryRepository,
      RoutingLogRepository routingLogRepository,
      DestinationDispatcherRegistry registry,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    this.entryRepository = entryRepository;
    this.routingLogRepository = routingLogRepository;
    this.registry = registry;
    this.eventPublisher = eventPublisher;
    this.requiresNewTxTemplate = requiresNewTxTemplate;
    this.clock = clock;
  }

  @Override
  public void routeAll(UUID feedbackId, List<ConfidenceGate.ScoredClassification> scored) {
    if (scored == null || scored.isEmpty()) {
      log.warn("routeAll called with empty classifications for feedbackId={}", feedbackId);
      return;
    }

    FeedbackEntry entry =
        entryRepository
            .findById(feedbackId)
            .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
    UUID userId = entry.getUserId();
    UUID traceId = entry.getTraceId();
    UiContextDto uiContext = toDto(entry.getUiContext());
    int attempt = entry.getClassificationAttempts();

    Set<Destination> touched = EnumSet.noneOf(Destination.class);
    boolean anyFailed = false;
    boolean anyNonFailed = false;

    for (ConfidenceGate.ScoredClassification s : scored) {
      Destination dest = s.classification().destination();
      UUID routingLogId = UUID.randomUUID();
      RoutingStatus outcome;
      try {
        outcome =
            requiresNewTxTemplate.execute(
                status -> routeOne(routingLogId, entry, userId, traceId, uiContext, s, attempt));
      } catch (RuntimeException unrecoverable) {
        // The routeOne tx itself blew up (e.g. unique-constraint on the log id, datasource down).
        // Persist a defensive FAILED row OUTSIDE the failed tx so the audit trail survives.
        log.error(
            "Catastrophic routeOne failure for feedbackId={} dest={}: {}",
            feedbackId,
            dest,
            unrecoverable.toString());
        persistFailureLog(
            routingLogId,
            entry,
            s,
            attempt,
            RoutingFailureKind.UNKNOWN,
            unrecoverable.getMessage());
        outcome = RoutingStatus.FAILED;
      }
      touched.add(dest);
      if (outcome == RoutingStatus.FAILED) {
        anyFailed = true;
      } else {
        anyNonFailed = true;
      }
    }

    reconcileAndPublish(feedbackId, userId, traceId, touched, anyFailed, anyNonFailed);
  }

  /**
   * Correction-replay entry point (ticket 01f §11). Routes one synthetic classification through the
   * same {@code REQUIRES_NEW} per-destination dispatch as {@link #routeAll}, returning the new
   * routing-log id + the dispatch outcome. Does NOT reconcile entry status or publish {@code
   * FeedbackProcessedEvent} — {@code correctMisclassification} owns that.
   */
  @Override
  public RouteReplayResult routeOneForReplay(
      UUID feedbackId, ConfidenceGate.ScoredClassification scored) {
    FeedbackEntry entry =
        entryRepository
            .findById(feedbackId)
            .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
    UUID userId = entry.getUserId();
    UUID traceId = entry.getTraceId();
    UiContextDto uiContext = toDto(entry.getUiContext());
    int attempt = entry.getClassificationAttempts();
    UUID routingLogId = UUID.randomUUID();

    DispatchResult result;
    try {
      result =
          requiresNewTxTemplate.execute(
              status ->
                  routeOneCapturing(
                      routingLogId, entry, userId, traceId, uiContext, scored, attempt));
    } catch (RuntimeException unrecoverable) {
      log.error(
          "Catastrophic replay routeOne failure for feedbackId={} dest={}: {}",
          feedbackId,
          scored.classification().destination(),
          unrecoverable.toString());
      persistFailureLog(
          routingLogId,
          entry,
          scored,
          attempt,
          RoutingFailureKind.UNKNOWN,
          unrecoverable.getMessage());
      result = DispatchResult.failed(RoutingFailureKind.UNKNOWN, unrecoverable.getMessage());
    }
    return new RouteReplayResult(routingLogId, result.status(), result.failureKind());
  }

  /**
   * Same persist→dispatch→update as {@link #routeOne} but returns the full {@link DispatchResult}
   * (the replay flow needs the {@code failureKind} to map the correction's {@code replayStatus}).
   * {@link #routeOne} is kept signature-stable for 01d's {@code routeAll}.
   */
  DispatchResult routeOneCapturing(
      UUID routingLogId,
      FeedbackEntry entry,
      UUID userId,
      UUID traceId,
      UiContextDto uiContext,
      ConfidenceGate.ScoredClassification scored,
      int attempt) {

    Instant routedAt = clock.instant();
    RoutingLogEntry logRow =
        RoutingLogEntry.builder()
            .id(routingLogId)
            .feedbackEntry(entry)
            .destination(scored.classification().destination())
            .confidence(scored.classification().confidence())
            .extractedFeedback(scored.classification().extractedFeedback())
            .structuredPayload(scored.classification().structuredPayload())
            .routingDecision(scored.decision())
            .status(RoutingStatus.PENDING)
            .classificationAttempt(attempt)
            .routedAt(routedAt)
            .build();
    routingLogRepository.save(logRow);

    DestinationDispatcher dispatcher = registry.resolve(scored.classification().destination());
    DispatchContext ctx =
        new DispatchContext(
            entry.getId(),
            userId,
            traceId,
            routingLogId,
            uiContext,
            scored.classification(),
            attempt);

    DispatchResult result;
    try {
      result = dispatcher.dispatch(ctx);
    } catch (Exception ex) {
      result = classifyException(ex);
    }

    logRow.setStatus(result.status());
    logRow.setActionTaken(truncate(result.actionTaken()));
    logRow.setDestinationResultJson(result.destinationResultJson());
    logRow.setFailureKind(result.failureKind());
    logRow.setFailureMessage(truncate(stripSecrets(result.failureMessage())));
    logRow.setCompletedAt(clock.instant());
    routingLogRepository.save(logRow);

    return result;
  }

  /**
   * Persist the PENDING log row, dispatch, then UPDATE the row with the dispatcher's outcome. Runs
   * inside the {@code requiresNewTxTemplate} the caller wraps it in.
   */
  RoutingStatus routeOne(
      UUID routingLogId,
      FeedbackEntry entry,
      UUID userId,
      UUID traceId,
      UiContextDto uiContext,
      ConfidenceGate.ScoredClassification scored,
      int attempt) {

    Instant routedAt = clock.instant();
    RoutingLogEntry logRow =
        RoutingLogEntry.builder()
            .id(routingLogId)
            .feedbackEntry(entry)
            .destination(scored.classification().destination())
            .confidence(scored.classification().confidence())
            .extractedFeedback(scored.classification().extractedFeedback())
            .structuredPayload(scored.classification().structuredPayload())
            .routingDecision(scored.decision())
            .status(RoutingStatus.PENDING)
            .classificationAttempt(attempt)
            .routedAt(routedAt)
            .build();
    routingLogRepository.save(logRow);

    DestinationDispatcher dispatcher = registry.resolve(scored.classification().destination());
    DispatchContext ctx =
        new DispatchContext(
            entry.getId(),
            userId,
            traceId,
            routingLogId,
            uiContext,
            scored.classification(),
            attempt);

    DispatchResult result;
    try {
      result = dispatcher.dispatch(ctx);
    } catch (Exception ex) {
      result = classifyException(ex);
    }

    logRow.setStatus(result.status());
    logRow.setActionTaken(truncate(result.actionTaken()));
    logRow.setDestinationResultJson(result.destinationResultJson());
    logRow.setFailureKind(result.failureKind());
    logRow.setFailureMessage(truncate(stripSecrets(result.failureMessage())));
    logRow.setCompletedAt(clock.instant());
    routingLogRepository.save(logRow);

    return result.status();
  }

  /**
   * Reconciles entry status + publishes the {@code FeedbackProcessedEvent} INSIDE the same {@code
   * REQUIRES_NEW} tx so {@code AFTER_COMMIT} listeners actually receive the event (round-3 retro:
   * events published outside any tx are silently dropped by Spring's {@code
   * TransactionalApplicationListenerSynchronization}).
   */
  void reconcileAndPublish(
      UUID feedbackId,
      UUID userId,
      UUID traceId,
      Set<Destination> touched,
      boolean anyFailed,
      boolean anyNonFailed) {

    SubmissionStatus next;
    if (anyFailed && anyNonFailed) {
      next = SubmissionStatus.PARTIALLY_FAILED;
    } else if (anyFailed) {
      next = SubmissionStatus.FAILED;
    } else {
      next = SubmissionStatus.ROUTED;
    }

    Instant now = clock.instant();
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          int rows =
              entryRepository.updateSubmissionStatusAndLastClassifiedAt(feedbackId, next, now);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
          eventPublisher.publishEvent(
              new FeedbackProcessedEvent(
                  feedbackId, userId, Set.copyOf(touched), anyFailed, false, traceId, now));
        });
  }

  /** Defensive write of a FAILED log row in its own short tx, used by the catastrophic catch. */
  void persistFailureLog(
      UUID routingLogId,
      FeedbackEntry entry,
      ConfidenceGate.ScoredClassification scored,
      int attempt,
      RoutingFailureKind kind,
      String message) {
    Instant now = clock.instant();
    try {
      requiresNewTxTemplate.executeWithoutResult(
          status -> {
            RoutingLogEntry row =
                RoutingLogEntry.builder()
                    .id(routingLogId)
                    .feedbackEntry(entry)
                    .destination(scored.classification().destination())
                    .confidence(scored.classification().confidence())
                    .extractedFeedback(scored.classification().extractedFeedback())
                    .structuredPayload(scored.classification().structuredPayload())
                    .routingDecision(scored.decision())
                    .status(RoutingStatus.FAILED)
                    .failureKind(kind)
                    .failureMessage(truncate(stripSecrets(message)))
                    .classificationAttempt(attempt)
                    .routedAt(now)
                    .completedAt(now)
                    .build();
            routingLogRepository.save(row);
          });
    } catch (RuntimeException doubleFault) {
      // We did our best. Log and move on so the rest of the destinations can be reconciled.
      log.error(
          "Double-fault persisting defensive failure log for feedbackId={} dest={}: {}",
          entry.getId(),
          scored.classification().destination(),
          doubleFault.toString());
    }
  }

  /**
   * Maps a dispatcher-thrown exception to a typed {@link DispatchResult}. Per LLD lines 776-784 +
   * ticket 01d §9. The available codebase exceptions are mapped pragmatically: there is no {@code
   * AiCircuitOpenException} / {@code AiCallFailedException} / {@code AiResponseInvalidException} in
   * this codebase — we use the actual {@code ai.exception.*} classes.
   */
  DispatchResult classifyException(Exception ex) {
    if (ex instanceof ConstraintViolationException) {
      return DispatchResult.failed(RoutingFailureKind.DESTINATION_VALIDATION, ex.getMessage());
    }
    if (ex instanceof RecipeFeedbackHandlerUnavailableException
        || ex instanceof PreferenceFeedbackBridgeUnavailableException
        || ex instanceof NutritionFeedbackBridgeUnavailableException
        || ex instanceof ProvisionsFeedbackBridgeUnavailableException
        || ex instanceof AiUnavailableException
        || ex instanceof AiInvalidResponseException
        || ex instanceof AiInvalidRequestException) {
      return DispatchResult.failed(RoutingFailureKind.AI_UNAVAILABLE, ex.getMessage());
    }
    if (ex instanceof DataAccessResourceFailureException
        || ex instanceof CannotAcquireLockException
        || ex instanceof QueryTimeoutException) {
      return DispatchResult.failed(RoutingFailureKind.TRANSIENT, ex.getMessage());
    }
    if (isDestinationBusinessException(ex)) {
      return DispatchResult.failed(RoutingFailureKind.DESTINATION_BUSINESS, ex.getMessage());
    }
    return DispatchResult.failed(RoutingFailureKind.UNKNOWN, ex.getMessage());
  }

  /**
   * Heuristic for "this looks like a wave-2 business exception from one of the destination
   * modules". No project-wide {@code MealPrepException} parent exists; we look at the package
   * prefix. {@code preference.exception.*}, {@code nutrition.exception.*}, {@code
   * provisions.exception.*}, {@code recipe.exception.*}, {@code adaptation.exception.*} all
   * qualify.
   */
  private static boolean isDestinationBusinessException(Exception ex) {
    String pkg = ex.getClass().getPackageName();
    return pkg.startsWith("com.example.mealprep.preference.exception")
        || pkg.startsWith("com.example.mealprep.nutrition.exception")
        || pkg.startsWith("com.example.mealprep.provisions.exception")
        || pkg.startsWith("com.example.mealprep.recipe.exception")
        || pkg.startsWith("com.example.mealprep.adaptation.exception");
  }

  static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= MESSAGE_MAX_LEN ? s : s.substring(0, MESSAGE_MAX_LEN);
  }

  static String stripSecrets(String s) {
    if (s == null) {
      return null;
    }
    String out = s;
    for (String pattern : SECRET_PATTERNS) {
      if (out.contains(pattern)) {
        out = out.replace(pattern, "[REDACTED]");
      }
    }
    return out;
  }

  private static UiContextDto toDto(UiContextDocument doc) {
    if (doc == null) {
      return null;
    }
    Screen screen = doc.screen();
    return new UiContextDto(
        screen,
        doc.recipeId(),
        doc.recipeVersion(),
        doc.mealSlotId(),
        doc.planId(),
        doc.referenceDate());
  }
}
