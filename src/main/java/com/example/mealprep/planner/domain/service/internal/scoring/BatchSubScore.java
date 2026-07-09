package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Batch-coordination sub-score. Algorithm LOCKED per LLD §BatchSubScore (2026-05-07):
 *
 * <pre>
 *   BatchSubScore = 1 - (count_distinct(slot.batch_cook_session_id) / len(plan.slots))
 * </pre>
 *
 * <p><b>null-as-single-bucket convention</b> (worth user review — ticket item 32): a slot whose
 * batch-cook session id is {@code null} is treated as a single shared "no-batch" bucket, NOT a
 * unique bucket per slot. So 21 slots with null session id → distinct = 1 → score {@code 1 - 1/21 ≈
 * 0.95}. This rewards consistency over fragmentation; the rejected alternative (null → unique
 * per-slot bucket → score 0) would punish the common no-batch case.
 *
 * <p><b>01e codebase divergence</b>: neither {@code SlotAssignment} nor {@code MealSlotSkeleton}
 * carries a {@code batchCookSessionId} field yet — the composer-side session-id assignment lands in
 * planner-01j (driven by {@code RecipeMetadataDto.batchCookable + lifestyle.prepDays}). Until then
 * every slot resolves to the single null/"no-batch" bucket, so the score is deterministically
 * {@code 1 - 1/N} for any non-empty plan. When 01j adds the field, swap {@link
 * #batchSessionId(SlotAssignment)} to read it; the distinct-set logic here already handles a real
 * id-per-slot mix correctly. Empty plan → {@code 1.0} (vacuous).
 */
@Component
class BatchSubScore implements SubScoreCalculator {

  @Override
  public String name() {
    return "batch";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return BigDecimal.ONE;
    }
    int slots = plan.assignments().size();
    if (batchCookingPreferred(ctx)) {
      // Meal-prep households are rewarded for REUSING recipes (cook once, eat across the week):
      // fewer distinct recipes → higher score. This positive concentration reward is what makes the
      // raised variety cap (see VarietyGate) actually bite — without it the variety sub-score's
      // diversity pull keeps every recipe at the per-week minimum. Distinct here = distinct non-null
      // recipe ids, exactly mirroring the incremental scorer's {@code recipeCounts.size()} so the
      // oracle invariant holds.
      Set<UUID> distinctRecipes = new HashSet<>();
      for (SlotAssignment a : plan.assignments()) {
        if (a.recipeId() != null) {
          distinctRecipes.add(a.recipeId());
        }
      }
      return finalScore(slots, distinctRecipes.size());
    }
    Set<UUID> distinctSessions = new HashSet<>();
    boolean sawNoBatch = false;
    for (SlotAssignment a : plan.assignments()) {
      UUID sessionId = batchSessionId(a);
      if (sessionId == null) {
        sawNoBatch = true; // collapses every null into ONE shared bucket
      } else {
        distinctSessions.add(sessionId);
      }
    }
    return finalScore(slots, distinctSessions.size(), sawNoBatch);
  }

  /**
   * True when the household's merged lifestyle config marks batch-cooking — switches the sub-score
   * from session-coordination (the 01e stub) to a recipe-reuse reward. Static + ctx-typed so the
   * incremental scorer applies the identical branch.
   */
  static boolean batchCookingPreferred(PlanCompositionContext ctx) {
    return ctx != null
        && ctx.mergedHouseholdPrefs() != null
        && ctx.mergedHouseholdPrefs().mergedLifestyleConfig() != null
        && ctx.mergedHouseholdPrefs().mergedLifestyleConfig().batchCookingPreferred();
  }

  /**
   * Weight multiplier applied to the batch sub-score for batch-cooking households. The recipe-reuse
   * reward and the variety sub-score's diversity pull are roughly equal-and-opposite at the base 1/7
   * weight, so the plan stays at the per-week minimum repetition; this lifts the reward enough to
   * actually concentrate the plan. Tuned for moderate concentration (~10–12 distinct recipes over a
   * 28-slot week); raise for tighter batching, lower for looser.
   */
  private static final BigDecimal BATCH_COOKING_WEIGHT_MULTIPLIER = BigDecimal.valueOf(2.0);

  /**
   * The batch-sub-score weight to apply for {@code ctx}: the base weight boosted by {@link
   * #BATCH_COOKING_WEIGHT_MULTIPLIER} when batch-cooking, else the base weight unchanged. Shared by
   * the whole-plan and incremental composites so both weight the batch term identically.
   */
  static BigDecimal effectiveWeight(BigDecimal baseWeight, PlanCompositionContext ctx) {
    return batchCookingPreferred(ctx)
        ? baseWeight.multiply(BATCH_COOKING_WEIGHT_MULTIPLIER)
        : baseWeight;
  }

  /** Core {@code 1 - distinct/slots}, clamped at 0. Empty plan → caller returns {@code 1.0}. */
  static BigDecimal finalScore(int slots, int distinct) {
    BigDecimal ratio =
        BigDecimal.valueOf(distinct).divide(BigDecimal.valueOf(slots), 6, RoundingMode.HALF_UP);
    return BigDecimal.ONE.subtract(ratio).max(BigDecimal.ZERO);
  }

  /**
   * The batch sub-score from the running slot count, distinct non-null session count, and the
   * sawNoBatch flag — the exact arithmetic the whole-plan {@link #compute} runs for the non-batch
   * path, so the incremental Stage-A scorer finalises identically. Empty plan → caller returns {@code
   * 1.0} before reaching here.
   */
  static BigDecimal finalScore(int slots, int distinctSessions, boolean sawNoBatch) {
    return finalScore(slots, distinctSessions + (sawNoBatch ? 1 : 0));
  }

  /**
   * Returns the batch-cook session id for an assignment. Always {@code null} in 01e — the field is
   * introduced by planner-01j's composer. Extracted as a seam so the swap is a one-liner. Exposed
   * package-private so the incremental scorer reads the SAME seam (keeps it from drifting when 01j
   * wires a real id).
   */
  static UUID batchSessionId(SlotAssignment assignment) {
    return null;
  }
}
