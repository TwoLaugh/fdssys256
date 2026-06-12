package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import java.util.UUID;

/**
 * Write API for the preference module's hard-constraints tier. Per the module's split-interface
 * design, taste-profile and lifestyle-config writes are NOT here — they live in their own dedicated
 * services ({@link TasteProfileUpdateService}, {@link LifestyleConfigUpdateService}) alongside the
 * preference-archive write surface ({@link PreferenceArchiveUpdateService}). This interface owns
 * only hard-constraints initialisation/replacement and the directive-sourced temporary-constraint
 * reversal.
 */
public interface PreferenceUpdateService {

  /**
   * Create the hard-constraints aggregate for a user with sensible defaults ({@code base =
   * "omnivore"}, empty children). Idempotent. Reached in-process only (health-directive SPI,
   * test-profile e2e seeder); the REST surface creates via the upsert-on-first-PUT path in {@link
   * #updateHardConstraints} instead.
   */
  HardConstraintsDto initialiseHardConstraints(UUID userId);

  /**
   * Replace the user's hard-constraints aggregate. Each field is diffed against the existing row;
   * one audit-log entry is written per actually-changed field; bumps {@code @Version} (mismatch
   * surfaces as {@code OptimisticLockingFailureException} → 409).
   *
   * <p><b>Upsert-on-first-PUT</b> (onboarding G1): when no row exists and {@code expectedVersion ==
   * 0}, the aggregate is initialised with the omnivore defaults and the request applied in the same
   * transaction (the create is an addition-only diff, so the GAP-04 Tier-1 removal gate can never
   * fire). When no row exists and {@code expectedVersion > 0} — a stale client, not a create intent
   * — throws {@code HardConstraintsNotFoundException} (404, unchanged). A concurrent create
   * double-submit loses the {@code user_id} unique race and surfaces as {@code
   * OptimisticLockingFailureException} → 409.
   */
  HardConstraintsDto updateHardConstraints(
      UUID userId, UpdateHardConstraintsRequest request, UUID actorUserId);

  /**
   * Reverse a temporary, directive-sourced hard constraint when its directive auto-expires.
   * Best-effort: a constraint the user has since edited away is a no-op. Writes an audit row (actor
   * = the directive's target user, since no system actor id exists in v1) and bumps
   * {@code @Version}. Idempotent — a second call (or one for a directive with no surviving rows)
   * writes no audit row, publishes no event, and does not throw.
   *
   * <p>Added in nutrition/01j as the preference-side reversal surface for the deferred nutrition
   * auto-expiry sweep (LLD line 1022). Wiring the {@code @Scheduled} sweep that CALLS this is a
   * fast follow-up, out of scope for 01j.
   */
  void removeTemporaryConstraint(UUID userId, UUID directiveId);
}
