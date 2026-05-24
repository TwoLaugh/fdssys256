package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import java.util.UUID;

/**
 * Write API for the taste profile aggregate. Each write produces:
 *
 * <ul>
 *   <li>one row in {@code preference_taste_profile_audit} (change provenance);
 *   <li>one row in {@code preference_taste_profile_versions} (document snapshot);
 *   <li>one {@code TasteProfileChangedEvent} published {@code AFTER_COMMIT}.
 * </ul>
 */
public interface TasteProfileUpdateService {

  /**
   * Onboarding seed — creates the row with sensible empty defaults if it does not yet exist;
   * idempotent. Called by the auth user-creation flow or an explicit "start fresh" admin endpoint.
   * Writes one audit row with {@code change_type = INITIALIZED}.
   */
  TasteProfileDto initialise(UUID userId);

  /**
   * User manual override — replaces the persisted document verbatim. Writes one audit row with
   * {@code change_type = MANUAL_OVERRIDE} and one version snapshot with {@code trigger = MANUAL}.
   *
   * @param userId the owner of the aggregate.
   * @param request the new document plus expected optimistic-version (409 on mismatch).
   * @param actorUserId who performed the change (equals {@code userId} for self-edits; differs for
   *     household-admin).
   * @return the post-write DTO.
   */
  TasteProfileDto applyManualOverride(
      UUID userId, UpdateTasteProfileRequest request, UUID actorUserId);

  /**
   * AI delta application — called in-process by the feedback bridge (NOT exposed via REST).
   * Validates the delta batch, applies it via {@code TasteProfileDeltaApplier} (whole-batch reject
   * on any invalid op → 422), enforces the {@code TasteProfileBudgetGuard} 2500-token budget (422
   * on overflow), then bumps {@code documentVersion} in lock-step with the document's internal
   * {@code version}, advances {@code feedbackCursor} / {@code basedOnFeedbackCount}, flips {@code
   * tasteVectorStatus = PENDING}, writes a version snapshot (real {@code deltasApplied}) + an
   * {@code actor_type = AI} / {@code AI_DELTA_APPLIED} audit row, and publishes {@code
   * TasteProfileChangedEvent} {@code AFTER_COMMIT}. {@code Archive} / {@code RePromote} ops also
   * write the preference archive in the same transaction. An empty delta batch is a no-op (no
   * version bump, no audit, no event). {@code @Transactional} REQUIRED so it joins the bridge's
   * REQUIRES_NEW template tx (preference-01f).
   *
   * @throws com.example.mealprep.preference.exception.TasteProfileNotFoundException if no profile
   *     exists for {@code userId} (404).
   * @throws com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException if a delta
   *     fails validation (422).
   * @throws com.example.mealprep.preference.exception.TasteProfileBudgetExceededException if the
   *     post-apply document exceeds the token budget (422).
   */
  TasteProfileDto applyDeltas(UUID userId, ApplyTasteProfileDeltasRequest request);

  /**
   * POST {@code /refresh-now} — fires an event the feedback module's {@code TasteProfileDeltaTask}
   * listens for. Writes one audit row with {@code change_type = REFRESH_TRIGGERED}. Returns the
   * current state immediately; the refresh itself is asynchronous.
   */
  TasteProfileDto triggerRefresh(
      UUID userId, TriggerTasteProfileRefreshRequest request, UUID actorUserId, UUID traceId);

  /**
   * Reverts to a prior {@code document_version}. Restores the target version's {@code
   * document_snapshot} as a <b>NEW</b> monotonic version ({@code change_type = ROLLED_BACK}; never
   * a version decrement — see {@code design/preference-model.md:419-421}). Re-stamps the restored
   * document's internal {@code version} / {@code lastUpdated} in lock-step with the new entity
   * version, resets {@code feedbackCursor} to the target version's {@code feedbackRangeStart} (the
   * deterministic replay anchor) and {@code basedOnFeedbackCount} to the target snapshot's value,
   * flips {@code tasteVectorStatus = PENDING} (the restored document needs re-embedding), writes a
   * {@code trigger = MANUAL} version snapshot (with a synthetic {@code {"op":"ROLLBACK",...}}
   * marker) + a {@code ROLLED_BACK} / {@code actor_type = USER} audit row, and publishes {@code
   * TasteProfileChangedEvent(ROLLED_BACK)} plus {@code TasteProfileRollbackReplayRequestedEvent}
   * AFTER_COMMIT. The forward feedback-replay is delegated to the feedback module via the latter
   * event (the preference module never replays feedback itself). {@code @Transactional} REQUIRED —
   * the rollback runs on a normal request thread (no {@code REQUIRES_NEW} needed).
   *
   * @param userId the owner of the aggregate.
   * @param targetDocumentVersion the historical version whose snapshot is restored.
   * @param expectedVersion the entity's current optimistic {@code @Version} (mismatch → 409).
   * @param actorUserId who performed the rollback (equals {@code userId} for self-rollback).
   * @return the post-rollback DTO (the restored document at the new monotonic version).
   * @throws com.example.mealprep.preference.exception.TasteProfileNotFoundException if no profile
   *     exists for {@code userId} (404).
   * @throws com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException if no
   *     snapshot exists for {@code targetDocumentVersion} (404).
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException if {@code
   *     expectedVersion} does not match the current optimistic version (409).
   */
  TasteProfileDto rollbackTasteProfile(
      UUID userId, int targetDocumentVersion, long expectedVersion, UUID actorUserId);
}
