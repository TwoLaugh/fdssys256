package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import java.util.UUID;

/**
 * Write API for the preference module — partial in 01a (hard-constraints only). Taste profile and
 * lifestyle config writes land in subsequent preference tickets.
 */
public interface PreferenceUpdateService {

  /**
   * Create the hard-constraints aggregate for a freshly-registered user with sensible defaults
   * ({@code base = "omnivore"}, empty children). Called from {@code auth} at user-creation; not
   * exposed via REST.
   */
  HardConstraintsDto initialiseHardConstraints(UUID userId);

  /**
   * Replace the user's hard-constraints aggregate. Each field is diffed against the existing row;
   * one audit-log entry is written per actually-changed field. Throws {@code
   * HardConstraintsNotFoundException} if no row exists; bumps {@code @Version} (mismatch surfaces
   * as {@code OptimisticLockingFailureException} → 409).
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
