package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.DirectiveKind;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage-A coordinator: runs source-biased strategies, concatenates output, renumbers indices
 * globally. Per ticket 01c §Stage A — strategies are deterministic in v1.
 *
 * <p>Source bias (LLD §Trigger 2 + §Trigger 4):
 *
 * <ul>
 *   <li>FEEDBACK + {@code ratingDelta.taste < -0.5} -> seed ingredient-swap (flavour-balance)
 *   <li>FEEDBACK + {@code ratingDelta.effortWorthIt < -0.5} -> seed method-simplification
 *   <li>PLAN_TIME + COST_DELTA / NUTRITION_DELTA / EQUIPMENT_OVERLAP / INGREDIENT_SWAP -> seed
 *       ingredient-swap
 *   <li>PLAN_TIME + TIME_DELTA -> seed method-simplification
 *   <li>otherwise: run all four strategies
 * </ul>
 */
@Component
public class CandidateGenerator {

  private static final BigDecimal NEG_HALF = BigDecimal.valueOf(-0.5);

  private final IngredientSwapStrategy swap;
  private final PortionAdjustStrategy portion;
  private final MethodSimplificationStrategy method;
  private final IngredientRemoveStrategy remove;

  public CandidateGenerator(
      IngredientSwapStrategy swap,
      PortionAdjustStrategy portion,
      MethodSimplificationStrategy method,
      IngredientRemoveStrategy remove) {
    this.swap = swap;
    this.portion = portion;
    this.method = method;
    this.remove = remove;
  }

  /** Run the appropriate strategies for {@code job.source} and return a globally-indexed list. */
  public List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context) {
    List<CandidateGenerationStrategy> strategies = pickStrategies(job, context);
    List<AdaptationCandidateDto> merged = new ArrayList<>();
    int index = 0;
    for (CandidateGenerationStrategy s : strategies) {
      for (AdaptationCandidateDto c : s.generate(job, context)) {
        merged.add(renumber(c, index++));
      }
    }
    return merged;
  }

  private List<CandidateGenerationStrategy> pickStrategies(
      AdaptationJob job, AdaptationContext context) {
    JobSource source = job.getSource();
    if (source == JobSource.FEEDBACK && context.ratingDelta() != null) {
      FeedbackJobRequest.RatingDeltaDto rd = context.ratingDelta();
      if (rd.taste() != null && rd.taste().compareTo(NEG_HALF) < 0) {
        return List.of(swap);
      }
      if (rd.effortWorthIt() != null && rd.effortWorthIt().compareTo(NEG_HALF) < 0) {
        return List.of(method);
      }
    }
    if (source == JobSource.PLAN_TIME && context.directive() != null) {
      PlanTimeRefineDirectiveRequest.RefineDirectiveDto d = context.directive();
      DirectiveKind kind = d.kind();
      if (kind == DirectiveKind.TIME_DELTA) {
        return List.of(method);
      }
      // Other plan-time kinds bias toward ingredient swap.
      return List.of(swap);
    }
    // Default: all strategies in declared order.
    return List.of(swap, portion, method, remove);
  }

  private AdaptationCandidateDto renumber(AdaptationCandidateDto c, int newIndex) {
    return new AdaptationCandidateDto(
        newIndex,
        c.proposedClassification(),
        c.proposedDiff(),
        c.rollup(),
        c.culinaryNotes(),
        c.nutritionalNotes(),
        c.characterPreservationScore(),
        c.estimatedConfidence(),
        c.plannerHints());
  }

  /** Quiet helper for tests + DI introspection. */
  Map<String, CandidateGenerationStrategy> registry() {
    return Map.of(
        swap.name(), swap, portion.name(), portion, method.name(), method, remove.name(), remove);
  }
}
