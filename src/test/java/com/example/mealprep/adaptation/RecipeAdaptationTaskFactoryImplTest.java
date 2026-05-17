package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTask;
import com.example.mealprep.adaptation.ai.internal.RecipeAdaptationTaskFactoryImpl;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipeAdaptationTaskFactoryImplTest {

  @Test
  void build_returns_recipe_adaptation_task_carrying_context() {
    AdaptationConfig config =
        new AdaptationConfig(
            5,
            10_000,
            8_000,
            12_000,
            3,
            3,
            14,
            new BigDecimal("0.50"),
            new BigDecimal("2.00"),
            null,
            30,
            "0 0 4 * * *",
            "0 30 4 * * *");
    RecipeAdaptationTaskFactoryImpl factory = new RecipeAdaptationTaskFactoryImpl(config);

    AdaptationJob job =
        AdaptationJob.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .source(JobSource.FEEDBACK)
            .priority(JobPriority.SYNC)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(JobStatus.RUNNING)
            .inputs(JsonNodeFactory.instance.objectNode())
            .traceId(UUID.randomUUID())
            .enqueuedAt(Instant.now())
            .build();
    AdaptationContext ctx =
        new AdaptationContext(
            "FEEDBACK",
            null,
            null,
            null,
            List.of(),
            null,
            "hc:none",
            null,
            new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
            "salty",
            null,
            null,
            null);

    AiTask<RecipeAdaptationResponse> task = factory.build(job, ctx);

    assertThat(task).isInstanceOf(RecipeAdaptationTask.class);
    assertThat(task.outputType()).isEqualTo(RecipeAdaptationResponse.class);
    assertThat(task.prompt().name()).isEqualTo("RecipeAdaptationTask");
    assertThat(task.variables().get("feedbackText")).isEqualTo("salty");
  }
}
