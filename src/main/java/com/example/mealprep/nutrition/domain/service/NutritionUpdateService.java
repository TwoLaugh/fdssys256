package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import java.util.UUID;

/**
 * Write API for the nutrition module's targets aggregate. {@code initialiseTargets} (auto-seed at
 * user creation with DRI defaults) ships in 01c — its DRI seed migration is deferred.
 */
public interface NutritionUpdateService {

  /**
   * Replace the user's nutrition targets wholesale. The request's {@code expectedVersion} is
   * matched against the row's current {@code @Version}; mismatch → {@link
   * org.springframework.dao.OptimisticLockingFailureException}.
   *
   * <p>One audit-log row is written per genuinely changed field (no-op fields → no row); writes are
   * atomic with the targets save (same {@code @Transactional}). On commit, a {@link
   * com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent} is published carrying the
   * set of changed field paths.
   *
   * @param userId the targets owner (resolved server-side)
   * @param request the full replacement payload
   * @param actorUserId the user performing the change — equal to {@code userId} for self-edits
   *     today; later sub-tickets layer admin / system actor flows
   */
  TargetsDto updateTargets(UUID userId, UpdateTargetsRequest request, UUID actorUserId);
}
