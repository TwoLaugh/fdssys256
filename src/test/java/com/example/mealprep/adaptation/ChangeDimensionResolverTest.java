package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.internal.ChangeDimensionResolver;
import com.example.mealprep.recipe.api.dto.ChangeAction;
import com.example.mealprep.recipe.api.dto.IngredientChangeDto;
import com.example.mealprep.recipe.api.dto.MethodChangeDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeDimensionResolverTest {

  private final ChangeDimensionResolver resolver = new ChangeDimensionResolver();

  @Test
  void feedback_low_taste_resolves_to_salt_level() {
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(BigDecimal.valueOf(-1.0), null, null, null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.SALT_LEVEL);
  }

  @Test
  void feedback_low_effort_resolves_to_method_simplification() {
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(null, BigDecimal.valueOf(-1.0), null, null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response()))
        .isEqualTo(ChangeDimension.METHOD_SIMPLIFICATION);
  }

  @Test
  void feedback_low_portion_resolves_to_portion_size() {
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(null, null, BigDecimal.valueOf(-1.0), null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.PORTION_SIZE);
  }

  @Test
  void unmatched_resolves_to_general_with_warn_log() {
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.IMPORT);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.GENERAL);
  }

  @Test
  void feedback_taste_exactly_neg_half_does_not_trigger_taste_branch() {
    // compareTo(NEG_HALF) < 0 is STRICT: -0.5 is NOT < -0.5, so the taste branch is skipped.
    // With no diff this falls through to GENERAL. A ConditionalsBoundary mutant (<= instead
    // of <) would wrongly return SALT_LEVEL here.
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(BigDecimal.valueOf(-0.5), null, null, null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.GENERAL);
  }

  @Test
  void feedback_effort_exactly_neg_half_does_not_trigger_effort_branch() {
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(null, BigDecimal.valueOf(-0.5), null, null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.GENERAL);
  }

  @Test
  void feedback_portion_exactly_neg_half_does_not_trigger_portion_branch() {
    AdaptationContext context =
        baseContext(
            new FeedbackJobRequest.RatingDeltaDto(null, null, BigDecimal.valueOf(-0.5), null));
    AdaptationJob job = job(JobSource.FEEDBACK);
    assertThat(resolver.resolve(job, context, response())).isEqualTo(ChangeDimension.GENERAL);
  }

  @Test
  void feedback_source_but_null_rating_delta_falls_through_to_diff() {
    // job.source == FEEDBACK but context.ratingDelta() == null -> skip the feedback block,
    // inspect the diff. Ingredient changes present -> PROTEIN.
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.FEEDBACK);
    RecipeAdaptationResponse resp =
        responseWithDiff(
            new RecipeDiffDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new IngredientChangeDto(ChangeAction.MODIFIED, null, null, "amount")),
                List.of(),
                List.of(),
                List.of()));
    assertThat(resolver.resolve(job, context, resp)).isEqualTo(ChangeDimension.PROTEIN);
  }

  @Test
  void diff_with_ingredient_changes_resolves_to_protein() {
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.IMPORT);
    RecipeAdaptationResponse resp =
        responseWithDiff(
            new RecipeDiffDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new IngredientChangeDto(ChangeAction.ADDED, null, null, "tofu")),
                List.of(),
                List.of(),
                List.of()));
    assertThat(resolver.resolve(job, context, resp)).isEqualTo(ChangeDimension.PROTEIN);
  }

  @Test
  void diff_with_only_method_changes_resolves_to_method_simplification() {
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.IMPORT);
    RecipeAdaptationResponse resp =
        responseWithDiff(
            new RecipeDiffDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(),
                List.of(new MethodChangeDto(ChangeAction.MODIFIED, 2, "fry", "bake")),
                List.of(),
                List.of()));
    assertThat(resolver.resolve(job, context, resp))
        .isEqualTo(ChangeDimension.METHOD_SIMPLIFICATION);
  }

  @Test
  void diff_present_but_empty_change_lists_falls_back_to_general() {
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.IMPORT);
    RecipeAdaptationResponse resp =
        responseWithDiff(
            new RecipeDiffDto(
                UUID.randomUUID(), UUID.randomUUID(), List.of(), List.of(), List.of(), List.of()));
    assertThat(resolver.resolve(job, context, resp)).isEqualTo(ChangeDimension.GENERAL);
  }

  @Test
  void null_response_falls_back_to_general() {
    AdaptationContext context = baseContext(null);
    AdaptationJob job = job(JobSource.IMPORT);
    assertThat(resolver.resolve(job, context, null)).isEqualTo(ChangeDimension.GENERAL);
  }

  private static RecipeAdaptationResponse responseWithDiff(RecipeDiffDto diff) {
    return new RecipeAdaptationResponse(
        0,
        AdaptationClassification.VERSION,
        "ok",
        "",
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.8),
        diff,
        null,
        List.of());
  }

  private static AdaptationContext baseContext(FeedbackJobRequest.RatingDeltaDto rd) {
    return new AdaptationContext(
        "IMPORT",
        null,
        null,
        null,
        List.of(),
        null,
        "v0",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        null,
        rd,
        null,
        null);
  }

  private static RecipeAdaptationResponse response() {
    return new RecipeAdaptationResponse(
        0,
        AdaptationClassification.VERSION,
        "ok",
        "",
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.8),
        null,
        null,
        List.of());
  }

  private static AdaptationJob job(JobSource source) {
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
}
