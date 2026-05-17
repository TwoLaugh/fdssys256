package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Multiplicative variety hard-gate. Per LLD §scoring: a recipe may appear at most {@code maxRepeat}
 * times across {@code plan.assignments}; any recipe exceeding that fails the gate (composite → 0).
 * {@code maxRepeat} is tunable via {@code mealprep.planner.scoring.variety.max-repeat} (default 2;
 * {@code @Min(1)}). Empty / null plan passes vacuously.
 */
@Component
class VarietyGate {

  private final PlannerProperties properties;

  VarietyGate(PlannerProperties properties) {
    this.properties = properties;
  }

  boolean passes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return true;
    }
    int maxRepeat = properties.scoring().variety().maxRepeat();
    Map<UUID, Integer> counts = new HashMap<>();
    for (SlotAssignment a : plan.assignments()) {
      if (a.recipeId() == null) {
        continue;
      }
      int next = counts.merge(a.recipeId(), 1, Integer::sum);
      if (next > maxRepeat) {
        return false;
      }
    }
    return true;
  }
}
