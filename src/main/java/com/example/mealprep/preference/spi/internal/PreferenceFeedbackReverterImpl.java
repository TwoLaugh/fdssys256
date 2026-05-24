package com.example.mealprep.preference.spi.internal;

import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.PreferenceException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Real {@link PreferenceFeedbackReverter} (feedback-01h §5-8). As a plain {@code @Component} it
 * out-ranks the feedback module's {@code NoopFeedbackRevertersConfiguration}
 * {@code @Bean @ConditionalOnMissingBean}, so with preference on the classpath the feedback service
 * resolves this bean. (The Noop stays in place for feedback-only test slices that don't load
 * preference.)
 *
 * <p>Best-effort taste-profile rollback when a PREFERENCE routing is corrected away. The original
 * routing applied an AI delta batch via {@code TasteProfileUpdateService.applyDeltas}, which
 * stamped a version snapshot keyed by the {@code feedback-<feedbackId>} origin trace (recorded on
 * the routing's {@code destinationResultJson} as {@code originTrace}). This reverter resolves that
 * snapshot, then calls {@link TasteProfileUpdateService#rollbackTasteProfile} to restore the
 * document state that preceded the delta apply (the snapshot's {@code documentVersion} minus one).
 *
 * <p><b>Transaction phase (decision-log 0010)</b>: invoked INSIDE {@code
 * correctMisclassification}'s default {@code @Transactional} (REQUIRED) bookkeeping tx — the
 * downstream {@code rollbackTasteProfile} is plain {@code @Transactional} (REQUIRED) so it JOINS
 * that tx; the rollback's audit row + version snapshot + {@code TasteProfileChangedEvent} commit
 * atomically with the {@code CORRECTED_AWAY} flip + the {@code MisclassificationCorrection} row. No
 * {@code REQUIRES_NEW} (that would detach the undo from the correction record).
 *
 * <p><b>Best-effort &amp; never-throws</b>: the SPI contract requires the reverter not to block the
 * correction. Every degraded branch (no result handle, no profile, no snapshot, version 1 with no
 * predecessor, optimistic-lock conflict from newer deltas applied on top, any preference domain
 * exception) is caught internally and logged WARN; the correction still records ground truth and
 * fires the synthetic replay. When newer deltas advanced the {@code documentVersion} past the
 * corrected one, a clean revert is impossible and {@code preference/01h}'s replay-from-cursor
 * (fired by the rollback event) is the designed reconciliation — this reverter triggers it and
 * accepts a partial result.
 */
@Component
public class PreferenceFeedbackReverterImpl implements PreferenceFeedbackReverter {

  private static final Logger log = LoggerFactory.getLogger(PreferenceFeedbackReverterImpl.class);

  private final TasteProfileQueryService tasteProfileQueryService;
  private final TasteProfileUpdateService tasteProfileUpdateService;
  private final TasteProfileVersionRepository versionRepository;

  public PreferenceFeedbackReverterImpl(
      TasteProfileQueryService tasteProfileQueryService,
      TasteProfileUpdateService tasteProfileUpdateService,
      TasteProfileVersionRepository versionRepository) {
    this.tasteProfileQueryService = tasteProfileQueryService;
    this.tasteProfileUpdateService = tasteProfileUpdateService;
    this.versionRepository = versionRepository;
  }

  @Override
  public void revert(RevertContext ctx) {
    UUID userId = ctx.userId();
    String originTrace = readOriginTrace(ctx.destinationResultJson());
    if (originTrace == null) {
      log.warn(
          "preference revert is log-only; no origin trace on routing {} (delta apply may have"
              + " failed/booked FAILED) — taste profile left as-is",
          ctx.originalRoutingId());
      return;
    }

    Optional<TasteProfileDto> profile = tasteProfileQueryService.getTasteProfile(userId);
    if (profile.isEmpty()) {
      log.warn(
          "preference revert is log-only; no taste profile for routing {} (trace {}) — nothing to"
              + " roll back",
          ctx.originalRoutingId(),
          originTrace);
      return;
    }
    TasteProfileDto current = profile.get();

    Optional<TasteProfileVersion> applied =
        versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            current.id(), originTrace);
    if (applied.isEmpty()) {
      log.warn(
          "preference revert is log-only; no version snapshot for trace {} on routing {} — taste"
              + " profile left as-is",
          originTrace,
          ctx.originalRoutingId());
      return;
    }

    int appliedVersion = applied.get().getDocumentVersion();
    int targetVersion = appliedVersion - 1;
    if (targetVersion < 1) {
      log.warn(
          "preference revert is log-only; delta apply created the first document version ({}) for"
              + " trace {} on routing {} — no predecessor to roll back to",
          appliedVersion,
          originTrace,
          ctx.originalRoutingId());
      return;
    }

    try {
      TasteProfileDto rolledBack =
          tasteProfileUpdateService.rollbackTasteProfile(
              userId, targetVersion, current.optimisticVersion(), userId);
      log.info(
          "preference revert rolled taste profile back to document version {} (from applied {}) for"
              + " trace {} on routing {}; new document version {}",
          targetVersion,
          appliedVersion,
          originTrace,
          ctx.originalRoutingId(),
          rolledBack.documentVersion());
    } catch (ObjectOptimisticLockingFailureException conflict) {
      // Newer deltas advanced the optimistic version past our read — a clean in-tx revert is
      // impossible. preference/01h's replay-from-cursor is the designed reconciliation; the
      // divergence is logged and the correction proceeds (best-effort per the LLD).
      log.warn(
          "preference revert best-effort only; taste profile advanced past the corrected version"
              + " (trace {}, routing {}) — rollback skipped, replay-from-cursor reconciles",
          originTrace,
          ctx.originalRoutingId(),
          conflict);
    } catch (PreferenceException domainFailure) {
      // Profile/version vanished between read and rollback, or another preference-domain failure.
      // Degrade gracefully — the WARN names the destination + correlation handle, not a stack
      // trace through the bridge.
      log.warn(
          "preference revert is log-only; rollback to document version {} for trace {} on routing"
              + " {} failed ({}) — taste profile left as-is",
          targetVersion,
          originTrace,
          ctx.originalRoutingId(),
          domainFailure.getMessage());
    }
  }

  /** The {@code feedback-<feedbackId>} origin trace the preference bridge records on the result. */
  private static String readOriginTrace(JsonNode destinationResultJson) {
    if (destinationResultJson == null) {
      return null;
    }
    JsonNode node = destinationResultJson.path("originTrace");
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
