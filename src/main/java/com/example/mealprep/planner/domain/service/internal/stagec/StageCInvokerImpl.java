package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.IndexedCandidateRollup;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default {@link StageCInvoker}. Package-private — the only cross-module/-package seam is the
 * {@link StageCInvoker} interface (consumed by composer 01j).
 *
 * <p><b>No {@code @Transactional}.</b> AI calls must run outside any DB transaction per the style
 * guide's locked rule (HLD Tier-1 decision: AI-in-transaction = no). The composer (01j) opens its
 * persistence transaction only <i>after</i> Stage C returns. Verified by the absence of
 * {@code @Transactional} on {@link #pickOne}.
 *
 * <p>Fallback is the <b>skip-and-flag</b> degradation pattern (style-guide §AI Service): on any AI
 * failure or an out-of-range LLM index, the deterministic top-scored candidate (index 0 — the
 * candidate list is pre-sorted DESC by composite score by 01d's beam search) is selected and {@link
 * StageCResult#fallback()} is {@code true}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class StageCInvokerImpl implements StageCInvoker {

  static final String FALLBACK_REASONING =
      "AI ranking unavailable; deterministic top-scored candidate selected.";
  static final String EMPTY_REASONING = "no candidates available";

  private final AiService aiService;

  @SuppressWarnings("unused") // retained for documentation / future per-call timeout wiring
  private final PlannerProperties properties;

  @Override
  public StageCResult pickOne(
      List<CandidatePlan> candidates,
      List<CandidatePlanRollupDto> rollups,
      PlanCompositionContext ctx,
      UUID traceId) {
    if (candidates.isEmpty()) {
      log.warn(
          "Stage C invoked with empty candidates list — returning deterministic empty fallback");
      return new StageCResult(0, EMPTY_REASONING, AugmentationSource.LLM, true);
    }
    if (candidates.size() != rollups.size()) {
      throw new IllegalArgumentException(
          "candidates and rollups must be same size (candidates="
              + candidates.size()
              + ", rollups="
              + rollups.size()
              + ")");
    }

    List<IndexedCandidateRollup> indexed =
        IntStream.range(0, candidates.size())
            .mapToObj(
                i -> new IndexedCandidateRollup(i, candidates.get(i).candidateId(), rollups.get(i)))
            .toList();

    String summary = buildConstraintsSummary(ctx);
    UUID primaryUserId = resolvePrimaryUserId(ctx);

    StageCPickTask task =
        new StageCPickTask(
            indexed,
            summary,
            resolveHouseholdSize(ctx),
            ctx.weekStartDate(),
            TriggerKind.USER_INITIATED,
            primaryUserId,
            traceId);

    try {
      StageCPickResponse response = aiService.execute(task);
      if (response.chosenIndex() < 0 || response.chosenIndex() >= candidates.size()) {
        log.warn(
            "Stage C returned out-of-range chosenIndex {} for N={}; falling back to deterministic",
            response.chosenIndex(),
            candidates.size());
        return deterministicFallback();
      }
      return new StageCResult(
          response.chosenIndex(), response.reasoning(), AugmentationSource.LLM, false);
    } catch (AiCostBudgetExceededException e) {
      log.info("Stage C: AI cost cap reached ({}); falling back to deterministic", e.getMessage());
      return deterministicFallback();
    } catch (AiUnavailableException e) {
      log.warn("Stage C: AI unavailable / transient failure; falling back to deterministic", e);
      return deterministicFallback();
    }
  }

  private StageCResult deterministicFallback() {
    // candidates are pre-sorted DESC by composite score by 01d's beam search → index 0 is top.
    return new StageCResult(0, FALLBACK_REASONING, AugmentationSource.LLM, true);
  }

  /**
   * Minimal one-line constraints summary for the v1 pilot prompt. Refinement is part of the
   * prompt-engineering follow-up (LLD §Out of Scope). Deterministic — no {@code Instant.now}, no
   * randomness — so the same context yields a byte-identical {@link StageCPickTask} payload.
   */
  private String buildConstraintsSummary(PlanCompositionContext ctx) {
    int size = resolveHouseholdSize(ctx);
    int allergenCount =
        ctx.hardConstraintsByUserId() == null ? 0 : ctx.hardConstraintsByUserId().size();
    return "Household of "
        + size
        + " people; "
        + allergenCount
        + " member hard-constraint profile(s); week starting "
        + ctx.weekStartDate()
        + ".";
  }

  /**
   * Household size: prefer the settings document's {@code defaultHeadcount}; fall back to the
   * member count (hard-constraint profiles, one per member); floor of 1.
   */
  private int resolveHouseholdSize(PlanCompositionContext ctx) {
    if (ctx.householdSettings() != null
        && ctx.householdSettings().document() != null
        && ctx.householdSettings().document().defaultHeadcount() != null) {
      return Math.max(1, ctx.householdSettings().document().defaultHeadcount());
    }
    int members = ctx.hardConstraintsByUserId() == null ? 0 : ctx.hardConstraintsByUserId().size();
    return Math.max(1, members);
  }

  /**
   * Primary user for cost attribution. The context exposes no explicit primary-user field, so
   * resolve the first member id (deterministic for a given context); {@code null} when no members
   * are wired (yields {@code Optional.empty()} on the task, which the dispatcher tolerates for
   * system-initiated work).
   */
  private UUID resolvePrimaryUserId(PlanCompositionContext ctx) {
    if (ctx.hardConstraintsByUserId() == null || ctx.hardConstraintsByUserId().isEmpty()) {
      return null;
    }
    return ctx.hardConstraintsByUserId().keySet().stream().sorted().findFirst().orElse(null);
  }
}
