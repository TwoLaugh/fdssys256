package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.StageCResult;
import java.util.List;
import java.util.UUID;

/**
 * Stage C of plan composition — LLM pick-of-N. Given the N Stage-A candidate plans (sorted DESC by
 * composite score) plus their per-day rollups, invokes {@code AiService} with {@code
 * StageCPickTask} (prompt #8) to ask the model which candidate best balances the household's
 * constraints, and returns its {@code chosenIndex + reasoning}.
 *
 * <p>On any AI failure ({@code AiUnavailableException}, cost-cap, transient-after-retries) or an
 * out-of-range LLM response, the implementation falls back to the deterministic top-scored
 * candidate (index 0) and flags {@link StageCResult#fallback()}.
 *
 * <p>Module-internal SPI consumed by the composer (planner-01j). Lives under {@code
 * domain/service/internal/stagec/} per the style guide's internal-helpers convention.
 */
public interface StageCInvoker {

  /**
   * Pick the best candidate.
   *
   * @param candidates Stage-A output, pre-sorted DESC by composite score (01d's beam search)
   * @param rollups per-candidate per-day rollups, index-aligned with {@code candidates}
   * @param context the plan-composition bundle (household settings, week, trace ids)
   * @param traceId decision-log correlation id for this composition run
   * @return the chosen index + reasoning + selection origin
   * @throws IllegalArgumentException if {@code candidates.size() != rollups.size()}
   */
  StageCResult pickOne(
      List<CandidatePlan> candidates,
      List<CandidatePlanRollupDto> rollups,
      PlanCompositionContext context,
      UUID traceId);
}
