package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.DirectiveKind;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.internal.CandidateGenerator;
import com.example.mealprep.adaptation.domain.service.internal.IngredientRemoveStrategy;
import com.example.mealprep.adaptation.domain.service.internal.IngredientSwapStrategy;
import com.example.mealprep.adaptation.domain.service.internal.MethodSimplificationStrategy;
import com.example.mealprep.adaptation.domain.service.internal.PortionAdjustStrategy;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.MethodStepDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CandidateGenerator} + the four strategies. */
class CandidateGeneratorTest {

  private final CandidateGenerator gen =
      new CandidateGenerator(
          new IngredientSwapStrategy(),
          new PortionAdjustStrategy(),
          new MethodSimplificationStrategy(),
          new IngredientRemoveStrategy());

  @Test
  void default_path_emits_candidates_for_all_strategies() {
    AdaptationJob job = jobOfSource(JobSource.IMPORT);
    AdaptationContext context = contextWithStandardRecipe();

    List<AdaptationCandidateDto> out = gen.generate(job, context);
    // 3 swaps for beef + 4 portion + 2 method + 1 remove for the optional ingredient = 10 total.
    assertThat(out).hasSizeGreaterThanOrEqualTo(9);
    // Indices are globally renumbered starting at 0.
    assertThat(out.get(0).index()).isZero();
    assertThat(out.get(out.size() - 1).index()).isEqualTo(out.size() - 1);
  }

  @Test
  void feedback_with_low_taste_only_runs_swap_strategy() {
    AdaptationJob job = jobOfSource(JobSource.FEEDBACK);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "FEEDBACK",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            "tastes flat",
            new FeedbackJobRequest.RatingDeltaDto(BigDecimal.valueOf(-1.0), null, null, null),
            null,
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    // beef has 3 swaps configured.
    assertThat(out).hasSize(3);
  }

  @Test
  void plan_time_time_delta_runs_method_simplification_only() {
    AdaptationJob job = jobOfSource(JobSource.PLAN_TIME);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "PLAN_TIME",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            null,
            null,
            new PlanTimeRefineDirectiveRequest.RefineDirectiveDto(
                DirectiveKind.TIME_DELTA, "shorten", JsonNodeFactory.instance.objectNode()),
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    // method-simplification emits 2 candidates: drop + collapse.
    assertThat(out).hasSize(2);
  }

  @Test
  void feedback_taste_exactly_neg_half_does_not_trigger_swap_only_bias() {
    // pickStrategies L70: `rd.taste().compareTo(NEG_HALF) < 0` is STRICT. At exactly -0.5,
    // compareTo == 0, so the swap-only branch is skipped and the default 4-strategy path runs.
    // The ConditionalsBoundary mutant (`< 0` -> `<= 0`) would wrongly return swap-only (3
    // candidates) here; asserting the full default fan-out (>=9) fails under that mutant.
    AdaptationJob job = jobOfSource(JobSource.FEEDBACK);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "FEEDBACK",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            "tastes flat",
            new FeedbackJobRequest.RatingDeltaDto(BigDecimal.valueOf(-0.5), null, null, null),
            null,
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    assertThat(out).hasSizeGreaterThanOrEqualTo(9);
  }

  @Test
  void feedback_with_low_effort_only_runs_method_strategy() {
    // pickStrategies L73-74: taste null (skip swap branch), effort < -0.5 -> method-only.
    // method-simplification emits exactly 2 candidates; the default path would emit >=9.
    AdaptationJob job = jobOfSource(JobSource.FEEDBACK);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "FEEDBACK",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            "too much effort",
            new FeedbackJobRequest.RatingDeltaDto(null, BigDecimal.valueOf(-1.0), null, null),
            null,
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    assertThat(out).hasSize(2);
  }

  @Test
  void feedback_effort_exactly_neg_half_does_not_trigger_method_only_bias() {
    // pickStrategies L73: `rd.effortWorthIt().compareTo(NEG_HALF) < 0` is STRICT. At exactly
    // -0.5, compareTo == 0, so the method-only branch is skipped and the default 4-strategy
    // path runs. The ConditionalsBoundary mutant (`< 0` -> `<= 0`) would wrongly return
    // method-only (2 candidates) here; asserting the full default fan-out (>=9) kills it.
    AdaptationJob job = jobOfSource(JobSource.FEEDBACK);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "FEEDBACK",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            "borderline effort",
            new FeedbackJobRequest.RatingDeltaDto(null, BigDecimal.valueOf(-0.5), null, null),
            null,
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    assertThat(out).hasSizeGreaterThanOrEqualTo(9);
  }

  @Test
  void plan_time_non_time_delta_biases_to_swap_only() {
    // pickStrategies L84: PLAN_TIME with a non-TIME_DELTA directive -> swap-only.
    // beef has 3 swaps; the default path would emit >=9. Asserting exactly 3 fails if the
    // L84 `return List.of(swap)` were removed/changed (it would fall through to all-4).
    AdaptationJob job = jobOfSource(JobSource.PLAN_TIME);
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationContext context =
        new AdaptationContext(
            "PLAN_TIME",
            base.recipe(),
            base.currentVersion(),
            base.fingerprint(),
            base.candidates(),
            base.softPreferencesHash(),
            base.hardConstraintsHash(),
            base.nutritionTargetsSummary(),
            base.knowledgeBundle(),
            null,
            null,
            new PlanTimeRefineDirectiveRequest.RefineDirectiveDto(
                DirectiveKind.COST_DELTA, "cheaper", JsonNodeFactory.instance.objectNode()),
            null);
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    assertThat(out).hasSize(3);
  }

  @Test
  void feedback_source_but_null_rating_delta_falls_through_to_default_fanout() {
    // pickStrategies L68: source==FEEDBACK but context.ratingDelta()==null -> skip the feedback
    // block entirely and run the default 4-strategy path.
    AdaptationJob job = jobOfSource(JobSource.FEEDBACK);
    AdaptationContext context = contextWithStandardRecipe(); // ratingDelta == null
    List<AdaptationCandidateDto> out = gen.generate(job, context);
    assertThat(out).hasSizeGreaterThanOrEqualTo(9);
  }

  @Test
  void withCandidates_returns_a_distinct_non_null_copy_carrying_the_new_list() {
    // AdaptationContext.withCandidates L50: NullReturnVals mutant returns null. Asserting the
    // result is non-null AND carries the replacement list (other fields preserved) kills it.
    AdaptationContext base = contextWithStandardRecipe();
    AdaptationCandidateDto c = gen.generate(jobOfSource(JobSource.IMPORT), base).get(0);
    AdaptationContext copy = base.withCandidates(List.of(c));
    assertThat(copy).isNotNull().isNotSameAs(base);
    assertThat(copy.candidates()).containsExactly(c);
    assertThat(copy.mode()).isEqualTo(base.mode());
    assertThat(copy.currentVersion()).isEqualTo(base.currentVersion());
  }

  @Test
  void portion_strategy_always_emits_four_factors() {
    PortionAdjustStrategy s = new PortionAdjustStrategy();
    List<AdaptationCandidateDto> out =
        s.generate(jobOfSource(JobSource.IMPORT), contextWithStandardRecipe());
    assertThat(out).hasSize(4);
    assertThat(out)
        .allSatisfy(
            c ->
                assertThat(c.proposedClassification()).isEqualTo(AdaptationClassification.VERSION));
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static AdaptationJob jobOfSource(JobSource source) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  private static AdaptationContext contextWithStandardRecipe() {
    IngredientDto beef =
        new IngredientDto(
            UUID.randomUUID(),
            0,
            "beef",
            "Beef chuck",
            BigDecimal.valueOf(500),
            "g",
            "diced",
            false,
            false,
            BigDecimal.valueOf(0.9));
    IngredientDto onion =
        new IngredientDto(
            UUID.randomUUID(),
            1,
            "onion",
            "Onion",
            BigDecimal.ONE,
            "ea",
            "chopped",
            true,
            false,
            BigDecimal.valueOf(0.95));
    MethodStepDto step1 =
        new MethodStepDto(UUID.randomUUID(), 1, "Brown the beef in a heavy pot for 6 minutes.", 6);
    MethodStepDto step2 = new MethodStepDto(UUID.randomUUID(), 2, "Add onions and stir.", 2);
    RecipeVersionDto version =
        new RecipeVersionDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            null,
            VersionTrigger.IMPORT,
            "initial",
            "pending",
            Instant.now(),
            "user:" + UUID.randomUUID(),
            null,
            List.of(beef, onion),
            List.of(step1, step2),
            new RecipeMetadataDto(
                2, 10, 20, 30, List.of("pot"), 3, 4, true, "italian", List.of("dinner")),
            new RecipeTagsDto("beef", "stovetop", null, List.of(), List.of()),
            null);
    return new AdaptationContext(
        "IMPORT",
        null,
        version,
        null,
        List.of(),
        null,
        "v0",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        null,
        null,
        null,
        null);
  }
}
