package com.example.mealprep.planner.domain.service.internal.reopt;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import java.util.List;
import java.util.UUID;

/**
 * Reopt-package SPI for the Stage-C "pick one of N" step (planner-01i invariant #8). The full LLM
 * implementation is {@code StageCInvoker} (planner-01g — not yet merged); per the 01i ticket
 * dependencies this is factored as a constructor collaborator with graceful degradation.
 *
 * <p>TODO(planner-01g): 01g supplies the production implementation that calls {@code
 * AiService.execute(StageCPickTask)} with the candidate rollups + constraints summary + {@code
 * trigger = MID_WEEK_REOPT}. Until then the coordinator treats this bean as optional ({@link
 * org.springframework.lang.Nullable}); when absent (or on {@code AiUnavailable} / {@code
 * TransientAiFailureException}) it falls back to the deterministic top-scored candidate (index 0 —
 * Stage-A returns candidates best-first) per {@code style-guide.md §AI Service} skip-and-flag. The
 * signature mirrors the LLD's {@code StageCInvoker.pickOne(...)} so 01g can adapt onto it without a
 * re-spin.
 */
public interface ReoptStageCInvoker {

  /**
   * Pick the index of the chosen candidate.
   *
   * @param candidates Stage-A's top-N, best-scored first; never empty
   * @param rollups per-candidate Stage-B rollups, index-aligned with {@code candidates}
   * @param context the narrowed composition context (carries the pinned slots as immutable context)
   * @param traceId the re-opt trace id
   * @return a result carrying the chosen index (0..N-1) and the reasoning string
   */
  Result pickOne(
      List<CandidatePlan> candidates,
      List<RollupSummaryDocument> rollups,
      PlanCompositionContext context,
      UUID traceId);

  /** Chosen index + free-text reasoning (recorded in the decision log). */
  record Result(int chosenIndex, String reasoning) {}
}
