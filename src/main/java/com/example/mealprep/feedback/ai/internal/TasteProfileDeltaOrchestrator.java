package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.feedback.ai.config.PreferenceDeltaProperties;
import com.example.mealprep.feedback.ai.dto.ClassifiedFeedbackEvent;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.example.mealprep.feedback.ai.task.PreferenceTasteProfileDeltaTask;
import com.example.mealprep.feedback.config.FeedbackTxTemplateConfig;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates one AI delta-generation run for a user (preference-01g §4-5). Drives:
 *
 * <ol>
 *   <li>load the current taste profile (skip with a structured log if absent — the lazily
 *       -initialised profile may not exist yet);
 *   <li>load the last {@value #RECENT_ARCHIVE_LIMIT} archive item keys (re-emergence detection);
 *   <li>gather the 1-10 PREFERENCE-routed feedback batch since the cursor (empty → skip the AI
 *       call, still advance the cursor — the feedback was processed);
 *   <li>run the mid-tier {@link PreferenceTasteProfileDeltaTask} via {@link AiService}
 *       <b>outside</b> any DB transaction; on {@code AiUnavailable}/budget the entries sit {@code
 *       pending_delta}, no exception escapes;
 *   <li>map the AI deltas to the wire shape and call {@code applyDeltas} (01f applier);
 *   <li>on {@code InvalidTasteProfileDelta} / {@code TasteProfileBudgetExceeded} retry once with a
 *       corrective re-prompt (when enabled), else log {@code delta_invalid} and skip (whole-batch
 *       reject; no partial application).
 * </ol>
 *
 * <p>Never throws past its own edge — both the trigger listeners and the scheduler treat a run as
 * fire-and-forget. The cursor reset is the caller's responsibility (it sequences after a successful
 * orchestration); this class returns a {@link RunResult} signalling what happened.
 */
@Component
public class TasteProfileDeltaOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(TasteProfileDeltaOrchestrator.class);

  /** Last-N archive keys handed to the prompt for re-emergence detection (ticket §4). */
  public static final int RECENT_ARCHIVE_LIMIT = 30;

  /** The mid-tier model identifier stamped on the apply request. */
  public static final String MODEL_TIER_USED = "mid";

  private final AiService aiService;
  private final AiToApplyDeltaMapper deltaMapper;
  private final PreferenceFeedbackBatchGatherer batchGatherer;
  private final PreferenceModule preferenceModule;
  private final PreferenceDeltaProperties properties;
  private final TransactionTemplate requiresNewTxTemplate;

  public TasteProfileDeltaOrchestrator(
      AiService aiService,
      AiToApplyDeltaMapper deltaMapper,
      PreferenceFeedbackBatchGatherer batchGatherer,
      PreferenceModule preferenceModule,
      PreferenceDeltaProperties properties,
      @Qualifier(FeedbackTxTemplateConfig.REQUIRES_NEW_TX_TEMPLATE)
          TransactionTemplate requiresNewTxTemplate) {
    this.aiService = aiService;
    this.deltaMapper = deltaMapper;
    this.batchGatherer = batchGatherer;
    this.preferenceModule = preferenceModule;
    this.properties = properties;
    this.requiresNewTxTemplate = requiresNewTxTemplate;
  }

  /** Outcome of a run — purely a log/test signal, not behavioural. */
  public enum RunResult {
    /** Profile missing — nothing to apply. */
    SKIPPED_NO_PROFILE,
    /** No PREFERENCE-routed feedback since the cursor — nothing to do. */
    SKIPPED_EMPTY_BATCH,
    /** AI returned no deltas (the conservative no-delta path) — cursor still advances. */
    NO_DELTAS,
    /** Deltas applied (version bumped). */
    APPLIED,
    /** AI unavailable / budget — entries left pending, retried later. */
    AI_UNAVAILABLE,
    /** Batch rejected by the applier even after a corrective retry — logged + skipped. */
    DELTA_INVALID
  }

  /**
   * Run a delta-update for {@code userId}. {@code trigger} stamps the version snapshot; {@code
   * traceOverride} (manual path) supplies an explicit origin trace, else the feedback batch range
   * is used. {@code rangeStartOverride}/{@code rangeEndOverride} (manual explicit-range path)
   * override the since-cursor default when both are present.
   */
  public RunResult run(
      UUID userId,
      TasteProfileTrigger trigger,
      UUID traceOverride,
      String rangeStartOverride,
      String rangeEndOverride) {
    Optional<TasteProfileDto> profileOpt =
        preferenceModule.tasteProfileQuery().getTasteProfile(userId);
    if (profileOpt.isEmpty()) {
      log.info(
          "preference delta run skipped — no taste profile (lazily-initialised) userId={} trigger={}",
          userId,
          trigger);
      return RunResult.SKIPPED_NO_PROFILE;
    }
    TasteProfileDto profile = profileOpt.get();

    List<String> recentArchiveIds = recentArchiveIds(userId);
    List<ClassifiedFeedbackEvent> batch = batchGatherer.gather(userId, sinceCursor(profile));
    if (batch.isEmpty()) {
      log.info(
          "preference delta run skipped — empty feedback batch userId={} trigger={}",
          userId,
          trigger);
      return RunResult.SKIPPED_EMPTY_BATCH;
    }

    UUID traceId = traceOverride != null ? traceOverride : firstFeedbackId(batch);
    String rangeStart =
        rangeStartOverride != null ? rangeStartOverride : "feedback-" + firstFeedbackId(batch);
    String rangeEnd =
        rangeEndOverride != null ? rangeEndOverride : "feedback-" + lastFeedbackId(batch);

    // Step 1 — AI call OUTSIDE any DB transaction (mirrors FeedbackClassificationListener).
    TasteProfileDeltaResponse response;
    try {
      response = callAi(profile, batch, recentArchiveIds, userId, traceId, null);
    } catch (AiUnavailableException | AiCostBudgetExceededException defer) {
      log.info(
          "preference delta run deferred — AI unavailable, entries left pending_delta userId={}"
              + " trigger={} reason={}",
          userId,
          trigger,
          defer.getClass().getSimpleName());
      return RunResult.AI_UNAVAILABLE;
    } catch (AiInvalidResponseException | AiInvalidRequestException terminal) {
      log.warn(
          "preference delta run terminal AI failure userId={} trigger={} reason={}: {}",
          userId,
          trigger,
          terminal.getClass().getSimpleName(),
          terminal.getMessage());
      return RunResult.DELTA_INVALID;
    }

    logWarnings(userId, response);

    List<TasteProfileDelta> wireDeltas = deltaMapper.toApplyDeltas(response.deltas());
    if (wireDeltas.isEmpty()) {
      // Empty AI response (or every op dropped) → no applyDeltas call, no version bump. The cursor
      // still advances (the feedback was processed) — that's the caller's responsibility.
      log.info(
          "preference delta run produced no applicable deltas userId={} trigger={} overall={}",
          userId,
          trigger,
          response.overallReasoning());
      return RunResult.NO_DELTAS;
    }

    // Step 2 — apply (01f applier opens its own @Transactional). On rejection, retry once with a
    // corrective re-prompt, else log delta_invalid + skip (no partial application).
    return applyWithCorrectiveRetry(
        userId,
        profile,
        batch,
        recentArchiveIds,
        traceId,
        trigger,
        rangeStart,
        rangeEnd,
        wireDeltas);
  }

  private RunResult applyWithCorrectiveRetry(
      UUID userId,
      TasteProfileDto profile,
      List<ClassifiedFeedbackEvent> batch,
      List<String> recentArchiveIds,
      UUID traceId,
      TasteProfileTrigger trigger,
      String rangeStart,
      String rangeEnd,
      List<TasteProfileDelta> wireDeltas) {
    try {
      apply(userId, wireDeltas, trigger, rangeStart, rangeEnd);
      return RunResult.APPLIED;
    } catch (InvalidTasteProfileDeltaException | TasteProfileBudgetExceededException rejected) {
      if (!properties.correctiveRetryEnabled()) {
        log.warn(
            "preference delta batch rejected (corrective retry disabled) — delta_invalid userId={}"
                + " trigger={} reason={}",
            userId,
            trigger,
            rejected.getMessage());
        return RunResult.DELTA_INVALID;
      }
      String hint = correctiveHint(rejected);
      log.info(
          "preference delta batch rejected — retrying once with corrective re-prompt userId={}"
              + " trigger={} reason={}",
          userId,
          trigger,
          rejected.getMessage());

      TasteProfileDeltaResponse retry;
      try {
        retry = callAi(profile, batch, recentArchiveIds, userId, traceId, hint);
      } catch (AiUnavailableException | AiCostBudgetExceededException defer) {
        log.info(
            "preference delta corrective retry deferred — AI unavailable userId={} trigger={}",
            userId,
            trigger);
        return RunResult.AI_UNAVAILABLE;
      } catch (AiInvalidResponseException | AiInvalidRequestException terminal) {
        log.warn(
            "preference delta corrective retry terminal AI failure userId={} trigger={}: {}",
            userId,
            trigger,
            terminal.getMessage());
        return RunResult.DELTA_INVALID;
      }

      logWarnings(userId, retry);
      List<TasteProfileDelta> retryDeltas = deltaMapper.toApplyDeltas(retry.deltas());
      if (retryDeltas.isEmpty()) {
        log.info(
            "preference delta corrective retry produced no applicable deltas userId={} trigger={}",
            userId,
            trigger);
        return RunResult.NO_DELTAS;
      }
      try {
        apply(userId, retryDeltas, trigger, rangeStart, rangeEnd);
        return RunResult.APPLIED;
      } catch (InvalidTasteProfileDeltaException | TasteProfileBudgetExceededException stillBad) {
        log.warn(
            "preference delta batch still rejected after corrective retry — delta_invalid,"
                + " skipping userId={} trigger={} reason={}",
            userId,
            trigger,
            stillBad.getMessage());
        return RunResult.DELTA_INVALID;
      }
    }
  }

  /**
   * Apply the wire deltas via the 01f applier, wrapped in a {@code REQUIRES_NEW} transaction. The
   * MANUAL trigger's listener fires AFTER_COMMIT, where a plain {@code @Transactional} (REQUIRED)
   * write silently fails to commit (decision-log 0010); the explicit {@code REQUIRES_NEW} boundary
   * guarantees the write commits regardless of trigger context. The AI call happens BEFORE this, so
   * no AI I/O ever runs inside the transaction. The applier's own {@code @Transactional(REQUIRED)}
   * joins this boundary so its document update + version snapshot + audit + archive rows commit
   * atomically. Validation rejections propagate out so the corrective-retry loop can catch them.
   */
  private void apply(
      UUID userId,
      List<TasteProfileDelta> wireDeltas,
      TasteProfileTrigger trigger,
      String rangeStart,
      String rangeEnd) {
    ApplyTasteProfileDeltasRequest request =
        new ApplyTasteProfileDeltasRequest(
            wireDeltas, trigger, rangeStart, rangeEnd, MODEL_TIER_USED);
    requiresNewTxTemplate.executeWithoutResult(
        status -> preferenceModule.tasteProfileUpdate().applyDeltas(userId, request));
  }

  private TasteProfileDeltaResponse callAi(
      TasteProfileDto profile,
      List<ClassifiedFeedbackEvent> batch,
      List<String> recentArchiveIds,
      UUID userId,
      UUID traceId,
      String correctiveHint) {
    return aiService.execute(
        new PreferenceTasteProfileDeltaTask(
            profile.document(), batch, recentArchiveIds, userId, traceId, correctiveHint));
  }

  /**
   * Corrective re-prompt hint per ticket §5: budget rejection → instruct the model to propose
   * Archive ops first; invalid delta → instruct it to re-check field paths + target existence.
   */
  private static String correctiveHint(RuntimeException rejected) {
    if (rejected instanceof TasteProfileBudgetExceededException) {
      return "Your previous response exceeded the taste-profile token budget. Propose Archive ops"
          + " for the least-supported existing items FIRST to make room, then only the most"
          + " strongly-evidenced additions.";
    }
    return "Your previous response was rejected by the validator: "
        + rejected.getMessage()
        + ". Re-check every fieldPath resolves to a real profile section and every Remove/Update/"
        + "Archive targets an item that actually exists in the current profile.";
  }

  private List<String> recentArchiveIds(UUID userId) {
    return preferenceModule.preferenceArchiveQuery().getFullArchive(userId).stream()
        .filter(e -> e.rePromotedAt() == null) // unpromoted keys only
        .limit(RECENT_ARCHIVE_LIMIT)
        .map(PreferenceArchiveEntryDto::itemKey)
        .toList();
  }

  /**
   * The instant floor for the feedback batch — the cursor's last delta-applied timestamp, so a run
   * only sees feedback newer than the last applied batch. {@code lastDeltaAppliedAt == null} (no
   * prior run) → null (gather from the beginning).
   */
  private static java.time.Instant sinceCursor(TasteProfileDto profile) {
    return profile.lastDeltaAppliedAt();
  }

  private static UUID firstFeedbackId(List<ClassifiedFeedbackEvent> batch) {
    return batch.get(0).feedbackId();
  }

  private static UUID lastFeedbackId(List<ClassifiedFeedbackEvent> batch) {
    return batch.get(batch.size() - 1).feedbackId();
  }

  private void logWarnings(UUID userId, TasteProfileDeltaResponse response) {
    if (!response.warnings().isEmpty()) {
      log.warn(
          "preference delta run AI warnings userId={} warnings={}", userId, response.warnings());
    }
  }
}
