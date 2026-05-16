package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.ai.internal.NoopRecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoopRecipeAdaptationTaskFactoryTest {

  @Test
  void noop_factory_throws_aiUnavailable_until_01e_ships() {
    RecipeAdaptationTaskFactory factory = new NoopRecipeAdaptationTaskFactory();
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
    AdaptationContext context =
        new AdaptationContext(
            "FEEDBACK",
            null,
            null,
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
    assertThatThrownBy(() -> factory.build(job, context))
        .isInstanceOf(AdaptationAiUnavailableException.class)
        .hasMessageContaining("01e");
  }
}
