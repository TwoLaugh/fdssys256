package com.example.mealprep.nutrition.spi;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module SPI for applying a directive routed to {@code mapsToModel = "preference_model"}.
 * Until {@code preference-01c} wires its impl, the {@code NoopDirectiveApplyTarget} bean answers
 * with HTTP 422 so the accept flow surfaces a clear "preference module not wired" error.
 *
 * <p>Implementations MUST join the caller's transaction (no {@code @Transactional(REQUIRES_NEW)})
 * so a downstream failure inside {@code applyPreferenceDirective} rolls back the directive status
 * update too.
 */
public interface DirectiveApplyTarget {

  /**
   * Apply a directive routed to {@code preference_model}.
   *
   * @param userId the directive's target user
   * @param instruction the effective instruction (already past the safety gate)
   * @param temporary whether the preference change should auto-expire
   * @param autoExpiresAt the expiry timestamp ({@code null} when {@code temporary == false})
   * @param directiveId the source directive's id — used for audit-log linkage
   * @param actorUserId the user who pressed accept
   */
  void applyPreferenceDirective(
      UUID userId,
      DirectiveInstructionDocument instruction,
      boolean temporary,
      Instant autoExpiresAt,
      UUID directiveId,
      UUID actorUserId);

  /**
   * Reverse a temporary {@code preference_model} directive whose {@code auto_expires_at} has passed
   * — the revert leg of the nutrition auto-expiry sweep (LLD Flow 8 line 1022). The implementation
   * maps to {@code PreferenceUpdateService.removeTemporaryConstraint(userId, directiveId)}:
   * best-effort and idempotent — a constraint the user has since edited away (or a directive with
   * no surviving directive-sourced rows) is a no-op, never throws.
   *
   * <p>Implementations MUST NOT throw on a missing/already-reverted directive; the sweep marks the
   * directive {@code EXPIRED} regardless, so a throw here would wrongly abort the per-directive
   * transaction. Joins the sweep's per-directive transaction.
   *
   * @param userId the directive's target user
   * @param directiveId the source directive's id (the provenance key on the temporary constraint)
   */
  void revertExpiredDirective(UUID userId, UUID directiveId);
}
