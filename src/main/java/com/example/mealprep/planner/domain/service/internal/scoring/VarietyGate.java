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
 * The cap is <b>per-household</b>: it defaults to {@code
 * mealprep.planner.scoring.variety.max-repeat} (default 2; {@code @Min(1)}) but is lifted when the
 * household's merged lifestyle config marks batch-cooking, so a meal-prepper can cook-once-eat-many
 * — see {@link #maxRepeat(PlanCompositionContext)}. Empty / null plan passes vacuously.
 */
@Component
class VarietyGate {

  /**
   * Per-recipe weekly cap for a batch-cooking household — a prep block of one dish across several
   * days. With the recipe-reuse reward ({@code BatchSubScore}) driving concentration toward the cap
   * floor, this cap is what sets the meal-prep intensity: 3 yields a <em>moderate</em> ~10 distinct
   * recipes over a 28-slot week (mostly 3×); 4+ is more aggressive (~7–8 distinct, heavy 4×).
   * Applied when {@code batchCookingPreferred}; the configured default still wins if it is already
   * higher.
   */
  private static final int BATCH_REPEAT_CAP = 3;

  private final PlannerProperties properties;

  VarietyGate(PlannerProperties properties) {
    this.properties = properties;
  }

  boolean passes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return true;
    }
    int maxRepeat = maxRepeat(ctx);
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

  /**
   * Per-recipe weekly repeat cap for this household — exposed (ctx-typed) so the incremental scorer
   * applies the identical cap. The configured default ({@code variety.max-repeat}, normally 2) is
   * lifted to {@value #BATCH_REPEAT_CAP} when the merged lifestyle config marks {@code
   * batchCookingPreferred} (the user has configured batch-cooking / prep days), so the planner may
   * repeat a recipe for a real prep block instead of being held to the variety-tuned default. With
   * no merged config the configured default applies (back-compatible).
   */
  int maxRepeat(PlanCompositionContext ctx) {
    int globalDefault = properties.scoring().variety().maxRepeat();
    if (ctx == null
        || ctx.mergedHouseholdPrefs() == null
        || ctx.mergedHouseholdPrefs().mergedLifestyleConfig() == null) {
      return globalDefault;
    }
    if (ctx.mergedHouseholdPrefs().mergedLifestyleConfig().batchCookingPreferred()) {
      return Math.max(globalDefault, BATCH_REPEAT_CAP);
    }
    return globalDefault;
  }
}
