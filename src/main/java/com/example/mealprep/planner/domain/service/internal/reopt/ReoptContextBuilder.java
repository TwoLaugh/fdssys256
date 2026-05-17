package com.example.mealprep.planner.domain.service.internal.reopt;

import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import java.util.List;
import java.util.UUID;

/**
 * Reopt-package SPI for narrowing a {@link PlanCompositionContext} to the non-pinned slots of an
 * active plan (planner-01i invariant #7). The full implementation belongs to the composer
 * (planner-01j's {@code PlanCompositionContextBuilder}), which owns the bundle-DTO fan-out and the
 * fresh {@code RecipePoolSnapshot} fetch.
 *
 * <p>TODO(planner-01j): 01j supplies the production implementation that fetches a fresh recipe pool
 * (recipes that became available since the original generation appear) and re-bundles
 * hard/soft/nutrition/provisions reads. Until 01j lands, the coordinator treats this bean as an
 * optional collaborator (constructor-injected, {@link org.springframework.lang.Nullable}); when
 * absent the re-opt is skipped with a WARN rather than running against a half-built context. The
 * 01i flow IT supplies a minimal in-line builder so the algorithm is still exercised end-to-end.
 */
public interface ReoptContextBuilder {

  /**
   * Build the narrowed composition context for a mid-week re-opt.
   *
   * @param activePlan the loaded active plan (hydrated days/slots)
   * @param nonPinnedSlots the regenerable slots — the search space
   * @param pinnedAssignments the verbatim assignments for pinned slots (immutable Stage-C context)
   * @param traceId the new trace id for this re-opt pass
   * @return a context whose {@code slotSkeletons} contains ONLY the non-pinned slots
   */
  PlanCompositionContext buildForReopt(
      Plan activePlan,
      List<MealSlot> nonPinnedSlots,
      List<SlotAssignment> pinnedAssignments,
      UUID traceId);
}
