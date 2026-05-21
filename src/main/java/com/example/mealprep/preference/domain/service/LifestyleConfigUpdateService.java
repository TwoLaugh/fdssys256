package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import java.util.UUID;

/**
 * Write API for the Tier-3 lifestyle config aggregate. Distinct from {@code
 * PreferenceUpdateService} (Tier 1 hard constraints).
 */
public interface LifestyleConfigUpdateService {

  /**
   * Create the lifestyle-config aggregate for a user with the supplied document, writing one audit
   * row at section level {@code "*"} for the whole document (per ticket §275). Idempotent — if the
   * aggregate already exists for {@code userId}, returns the existing one without mutation.
   */
  LifestyleConfigDto initialise(UUID userId, UpdateLifestyleConfigRequest request);

  /**
   * Replace the user's lifestyle-config document. The diff fires section-by-section; one audit row
   * per genuinely-changed top-level section. Throws {@code LifestyleConfigNotFoundException} if no
   * row exists; an {@code expectedVersion} mismatch surfaces as {@code
   * OptimisticLockingFailureException} → 409.
   */
  LifestyleConfigDto update(UUID userId, UpdateLifestyleConfigRequest request, UUID actorUserId);

  /**
   * Reset {@code lastReviewPromptAt} to NULL — the user has acknowledged the behavioural-drift
   * nudge. Bumps {@code @Version}. Throws {@code LifestyleConfigNotFoundException} if no row
   * exists.
   */
  LifestyleConfigDto markReviewed(UUID userId);
}
