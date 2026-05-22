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
   * AI delta application — called in-process by the feedback bridge (NOT exposed via REST). 01c
   * ships a stub: the underlying {@code TasteProfileDeltaApplier} throws {@link
   * UnsupportedOperationException} with a reference to the deferred {@code 01c-delta-applier}
   * ticket. The bridge in {@code tickets/feedback/01g} is responsible for not calling this until
   * the deferred ticket lands.
   */
  TasteProfileDto applyDeltas(UUID userId, ApplyTasteProfileDeltasRequest request);

  /**
   * POST {@code /refresh-now} — fires an event the feedback module's {@code TasteProfileDeltaTask}
   * listens for. Writes one audit row with {@code change_type = REFRESH_TRIGGERED}. Returns the
   * current state immediately; the refresh itself is asynchronous.
   */
  TasteProfileDto triggerRefresh(
      UUID userId, TriggerTasteProfileRefreshRequest request, UUID actorUserId, UUID traceId);
}
