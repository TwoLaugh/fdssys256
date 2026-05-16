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
