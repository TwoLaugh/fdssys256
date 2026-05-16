package com.example.mealprep.planner.domain.service.internal.beamsearch;

import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sorts expanded beam entries by {@code currentScore DESC} and retains the top {@code width}. Ties
 * broken by the last appended {@code recipeId} ascending — deterministic so canonical fixtures
 * always produce identical search output.
 *
 * <p>Pure function, no DB; covered by {@code BeamPrunerTest}.
 */
@Component
class BeamPruner {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  List<PartialPlan> retainTop(List<PartialPlan> expanded, int width) {
    if (expanded.isEmpty()) {
      return List.of();
    }
    return expanded.stream()
        .sorted(
            Comparator.comparing(PartialPlan::currentScore, Comparator.reverseOrder())
                .thenComparing(BeamPruner::lastRecipeId))
        .limit(width)
        .toList();
  }

  private static UUID lastRecipeId(PartialPlan p) {
    List<SlotAssignment> a = p.assignments();
    if (a.isEmpty()) {
      return ZERO_UUID;
    }
    return a.get(a.size() - 1).recipeId();
  }
}
