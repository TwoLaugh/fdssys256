package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.config.FeedbackAsyncConfig;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Async listener driving Flow 2 of the LLD: triggered AFTER_COMMIT on {@link
 * FeedbackSubmittedEvent}, classifies the entry, applies the {@link ConfidenceGate}, and writes the
 * resulting state transition + (on any &lt;0.5) a {@link ClarificationQuery}.
 *
 * <p>The listener method itself is <strong>not</strong> {@code @Transactional} (round-7 gotcha) —
 * the helper methods each open a {@code REQUIRES_NEW} transaction via the injected {@link
 * TransactionTemplate}. The AI call runs strictly outside any transaction.
 *
 * <p>{@code FeedbackProcessedEvent} is published from the clarification, empty-classifications, and
 * terminal-failure branches; the all-{@code >=0.5} happy path hands off to {@link FeedbackRouter}
 * which publishes once routing is complete (feedback-01d).
 */
@Component
public class FeedbackClassificationListener {

  private static final Logger log = LoggerFactory.getLogger(FeedbackClassificationListener.class);

  /** Canned clarification question text — locked phrasing from HLD §Confidence handling. */
  public static final String CLARIFICATION_QUESTION_TEXT =
      "I'm not sure what to do with this. Did you mean...";

  /** Clarification TTL — locked at 7 days per LLD line 200. */
  public static final Duration CLARIFICATION_TTL = Duration.ofDays(7);

  private final FeedbackClassifier classifier;
  private final ConfidenceGate confidenceGate;
  private final FeedbackEntryRepository entryRepository;
  private final ClarificationQueryRepository clarificationRepository;
  private final FeedbackRouter router;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate requiresNewTxTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public FeedbackClassificationListener(
      FeedbackClassifier classifier,
      ConfidenceGate confidenceGate,
      FeedbackEntryRepository entryRepository,
      ClarificationQueryRepository clarificationRepository,
      FeedbackRouter router,
      ApplicationEventPublisher eventPublisher,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate,
      ObjectMapper objectMapper,
      Clock clock) {
    this.classifier = classifier;
    this.confidenceGate = confidenceGate;
    this.entryRepository = entryRepository;
    this.clarificationRepository = clarificationRepository;
    this.router = router;
    this.eventPublisher = eventPublisher;
    this.requiresNewTxTemplate = requiresNewTxTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)
  public void onFeedbackSubmitted(FeedbackSubmittedEvent event) {
    classifyEntry(event.feedbackId(), event.userId(), event.traceId());
  }

  /** Visible for unit/integration testing. */
  public void classifyEntry(UUID feedbackId, UUID userId, UUID traceId) {
    // Step 1 — mark CLASSIFYING + increment attempts (REQUIRES_NEW, commits before AI call).
    // Native UPDATE bypasses Hibernate's @Version dirty-check which races with the publisher's
    // persistence context on AFTER_COMMIT (round-8 retro: StaleObjectStateException pattern).
    // We re-load AFTER the update so downstream steps see the fresh state.
    FeedbackEntry entry =
        requiresNewTxTemplate.execute(
            status -> {
              int rows =
                  entryRepository.updateSubmissionStatusAndIncrementAttempts(
                      feedbackId, SubmissionStatus.CLASSIFYING);
              if (rows == 0) {
                throw new FeedbackEntryNotFoundException(feedbackId);
              }
              return entryRepository
                  .findById(feedbackId)
                  .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
            });

    // Step 2 — build context (no I/O, outside any tx).
    FeedbackClassificationContext context = buildContext(entry);

    // Step 3 — AI call.
    ClassificationResult result;
    try {
      result = classifier.classify(context);
    } catch (AiUnavailableException | AiCostBudgetExceededException defer) {
      revertToReceived(feedbackId);
      log.info(
          "AI unavailable / budget gate; reverting entry {} to RECEIVED, traceId={}, reason={}",
          feedbackId,
          traceId,
          defer.getClass().getSimpleName());
      return;
    } catch (AiInvalidResponseException | AiInvalidRequestException terminal) {
      markFailed(feedbackId, userId, traceId);
      log.warn(
          "Terminal AI failure for entry {}, traceId={}, reason={}: {}",
          feedbackId,
          traceId,
          terminal.getClass().getSimpleName(),
          terminal.getMessage());
      return;
    }

    // Step 4 — gate + persist + hand-off.
    ConfidenceGate.GateResult gate = confidenceGate.evaluate(result);
    if (gate.anyBelowThreshold()) {
      queueClarification(feedbackId, userId, gate, traceId);
    } else if (gate.allEmpty()) {
      markRoutedEmpty(feedbackId, userId, traceId);
    } else {
      markClassifiedAndHandOff(feedbackId, gate);
    }
  }

  // ---------------- helpers ----------------

  private FeedbackClassificationContext buildContext(FeedbackEntry entry) {
    UiContextDto dto = toDto(entry.getUiContext());
    return new FeedbackClassificationContext(
        entry.getUserId(),
        entry.getTraceId(),
        entry.getText(),
        dto,
        Optional.empty(),
        Optional.<Destination>empty(),
        entry.getClassificationAttempts());
  }

  private static UiContextDto toDto(UiContextDocument doc) {
    if (doc == null) {
      return null;
    }
    return new UiContextDto(
        doc.screen(),
        doc.recipeId(),
        doc.recipeVersion(),
        doc.mealSlotId(),
        doc.planId(),
        doc.referenceDate());
  }

  private void revertToReceived(UUID feedbackId) {
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          int rows =
              entryRepository.updateSubmissionStatusAndDecrementAttempts(
                  feedbackId, SubmissionStatus.RECEIVED);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
        });
  }

  private void markFailed(UUID feedbackId, UUID userId, UUID traceId) {
    Instant now = clock.instant();
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          int rows =
              entryRepository.updateSubmissionStatusAndLastClassifiedAt(
                  feedbackId, SubmissionStatus.FAILED, now);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
          // Publish INSIDE the tx so @TransactionalEventListener(AFTER_COMMIT) listeners fire on
          // tx commit. Spring silently drops AFTER_COMMIT events published outside any tx.
          eventPublisher.publishEvent(
              new FeedbackProcessedEvent(feedbackId, userId, Set.of(), true, false, traceId, now));
        });
  }

  private void queueClarification(
      UUID feedbackId, UUID userId, ConfidenceGate.GateResult gate, UUID traceId) {
    Instant now = clock.instant();
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          // Use a managed reference to the entry only as the FK target for the clarification
          // child row; we do NOT mutate it through Hibernate — the status flip uses native UPDATE
          // (round-8 retro: avoids @Version race with publisher's persistence context).
          FeedbackEntry e =
              entryRepository
                  .findById(feedbackId)
                  .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));

          List<JsonNode> options =
              gate.classifications().stream()
                  .filter(s -> s.decision() == RoutingDecision.CLARIFICATION_QUEUED)
                  .<JsonNode>map(this::buildOptionNode)
                  .toList();

          ClarificationQuery query =
              ClarificationQuery.builder()
                  .id(UUID.randomUUID())
                  .feedbackEntry(e)
                  .classifierOptionsJson(objectMapper.valueToTree(options))
                  .questionText(CLARIFICATION_QUESTION_TEXT)
                  .status(ClarificationStatus.PENDING)
                  .expiresAt(now.plus(CLARIFICATION_TTL))
                  .build();
          clarificationRepository.save(query);

          int rows =
              entryRepository.updateSubmissionStatusAndLastClassifiedAt(
                  feedbackId, SubmissionStatus.CLARIFICATION_PENDING, now);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
          eventPublisher.publishEvent(
              new FeedbackProcessedEvent(feedbackId, userId, Set.of(), false, true, traceId, now));
        });
  }

  private JsonNode buildOptionNode(ConfidenceGate.ScoredClassification scored) {
    ObjectNode opt = objectMapper.createObjectNode();
    opt.put("destination", scored.classification().destination().name());
    opt.put("snippet", scored.classification().extractedFeedback());
    opt.put(
        "classifierJustification",
        "confidence " + scored.classification().confidence().toPlainString());
    return opt;
  }

  private void markRoutedEmpty(UUID feedbackId, UUID userId, UUID traceId) {
    Instant now = clock.instant();
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          int rows =
              entryRepository.updateSubmissionStatusAndLastClassifiedAt(
                  feedbackId, SubmissionStatus.ROUTED, now);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
          eventPublisher.publishEvent(
              new FeedbackProcessedEvent(feedbackId, userId, Set.of(), false, false, traceId, now));
        });
  }

  private void markClassifiedAndHandOff(UUID feedbackId, ConfidenceGate.GateResult gate) {
    Instant now = clock.instant();
    requiresNewTxTemplate.executeWithoutResult(
        status -> {
          int rows =
              entryRepository.updateSubmissionStatusAndLastClassifiedAt(
                  feedbackId, SubmissionStatus.CLASSIFIED, now);
          if (rows == 0) {
            throw new FeedbackEntryNotFoundException(feedbackId);
          }
        });
    // Hand to router (Noop in 01c, real impl in 01d). 01d's router publishes
    // FeedbackProcessedEvent.
    router.routeAll(feedbackId, gate.classifications());
  }
}
