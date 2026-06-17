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
   * The batch sub-score from the running slot count, distinct non-null session count, and the
   * sawNoBatch flag — the exact arithmetic the whole-plan {@link #compute} runs, so the incremental
   * Stage-A scorer (which carries a running slot count + sawNoBatch; distinctSessions is always 0 in
   * 01e since {@link #batchSessionId} returns null) finalises identically. Empty plan → caller
   * returns {@code 1.0} before reaching here.
   */
  static BigDecimal finalScore(int slots, int distinctSessions, boolean sawNoBatch) {
    int distinct = distinctSessions + (sawNoBatch ? 1 : 0);
    BigDecimal ratio =
        BigDecimal.valueOf(distinct).divide(BigDecimal.valueOf(slots), 6, RoundingMode.HALF_UP);
    return BigDecimal.ONE.subtract(ratio).max(BigDecimal.ZERO);
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
