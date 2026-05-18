package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.adaptation.api.dto.DirectiveKind;
import com.example.mealprep.adaptation.api.dto.PlanConstraintsSnapshotDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest.RefineDirectiveDto;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RefineDirectiveProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stage-D mapping helper (planner-01j). Converts the planner-local raw {@link
 * RefineDirectiveProposal} (the Phase-2 LLM's output shape) into the adaptation pipeline's
 * cross-module {@link PlanTimeRefineDirectiveRequest} per {@code
 * tickets/WAVE3-NAMING-RECONCILIATION.md}.
 *
 * <p>The composer owns the directive&rarr;request assembly (recipeId, planId, slotId, constraints
 * snapshot, parentDecisionId, traceId) — Phase 2 only emits the local proposal. The {@code kind}
 * mapping is the one piece of semantic translation: the LLM emits free-text {@code type} strings
 * ({@code SUBSTITUTE_INGREDIENT}, {@code REDUCE_TIME}); these map onto the typed {@link
 * DirectiveKind} the adaptation pipeline understands.
 */
@Component
class RefineDirectiveMapper {

  private final ObjectMapper objectMapper;
  private final Clock clock;

  RefineDirectiveMapper(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** Map the LLM's raw {@code type} string onto the adaptation pipeline's {@link DirectiveKind}. */
  DirectiveKind mapKind(String rawType) {
    if (rawType == null) {
      return DirectiveKind.INGREDIENT_SWAP;
    }
    return switch (rawType.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "SUBSTITUTE_INGREDIENT", "INGREDIENT_SWAP", "SWAP" -> DirectiveKind.INGREDIENT_SWAP;
      case "REDUCE_TIME", "TIME_DELTA", "SHORTEN_TIME" -> DirectiveKind.TIME_DELTA;
      case "REDUCE_COST", "COST_DELTA", "CHEAPER" -> DirectiveKind.COST_DELTA;
      case "NUTRITION_DELTA", "RAISE_PROTEIN", "ADJUST_NUTRITION" -> DirectiveKind.NUTRITION_DELTA;
      case "EQUIPMENT_OVERLAP", "EQUIPMENT" -> DirectiveKind.EQUIPMENT_OVERLAP;
      default -> DirectiveKind.INGREDIENT_SWAP;
    };
  }

  /**
   * Build the {@code targetDelta} JSON for a directive. Shape depends on {@code kind} — see {@link
   * RefineDirectiveDto} javadoc. Ingredient swaps carry {@code {from, to}}; time reductions carry
   * {@code {currentMin, targetMin}}.
   */
  ObjectNode targetDelta(RefineDirectiveProposal proposal, DirectiveKind kind) {
    ObjectNode node = objectMapper.createObjectNode();
    switch (kind) {
      case INGREDIENT_SWAP -> {
        node.put("from", proposal.fromIngredientKey());
        node.put("to", proposal.toIngredientKey());
      }
      case TIME_DELTA -> {
        if (proposal.currentTimeMin() != null) {
          node.put("currentMin", proposal.currentTimeMin());
        }
        if (proposal.targetTimeMin() != null) {
          node.put("targetMin", proposal.targetTimeMin());
        }
      }
      default -> {
        // COST_DELTA / NUTRITION_DELTA / EQUIPMENT_OVERLAP carry no structured payload from the
        // current Phase-2 proposal shape — the description carries the intent.
      }
    }
    return node;
  }

  /** Pin the planner's constraint world at Stage-D entry so the pipeline sees what we saw. */
  PlanConstraintsSnapshotDto constraintsSnapshot(PlanCompositionContext context) {
    BigDecimal weeklyBudgetGbp = null;
    if (context.provisions() != null
        && context.provisions().budget() != null
        && context.provisions().budget().weeklyTarget() != null) {
      weeklyBudgetGbp = context.provisions().budget().weeklyTarget();
    }
    Set<String> equipment = Set.of();
    Map<String, BigDecimal> nutritionTargets = new HashMap<>();
    return new PlanConstraintsSnapshotDto(
        objectMapper.createObjectNode(),
        weeklyBudgetGbp,
        equipment,
        nutritionTargets,
        clock.instant());
  }

  /**
   * Assemble the full cross-module Stage-D request.
   *
   * @param proposal the Phase-2 refine-directive proposal
   * @param recipeId the recipe currently scheduled in the affected slot
   * @param userId the requesting user
   * @param planId the (new) plan id
   * @param slotId the affected slot
   * @param parentDecisionId the composer's decision-log id (may be null until 01l)
   * @param context the frozen composition context (constraint snapshot source)
   */
  PlanTimeRefineDirectiveRequest toRequest(
      RefineDirectiveProposal proposal,
      UUID recipeId,
      UUID userId,
      UUID planId,
      UUID slotId,
      @Nullable UUID parentDecisionId,
      PlanCompositionContext context) {
    DirectiveKind kind = mapKind(proposal.type());
    RefineDirectiveDto directive =
        new RefineDirectiveDto(kind, proposal.reasoning(), targetDelta(proposal, kind));
    return new PlanTimeRefineDirectiveRequest(
        recipeId,
        userId,
        planId,
        slotId,
        directive,
        constraintsSnapshot(context),
        // Stage-D request requires a non-null parentDecisionId; until 01l's writer is wired the
        // composer passes its trace id as a stable correlation anchor.
        parentDecisionId != null ? parentDecisionId : context.traceId(),
        context.traceId());
  }
}
