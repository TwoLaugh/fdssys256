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
}
