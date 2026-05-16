package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLlmInvoker;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdaptationLlmInvokerTest {

  @Test
  @SuppressWarnings("unchecked")
  void wraps_ai_unavailable_exception() {
    AiService aiService = mock(AiService.class);
    RecipeAdaptationTaskFactory factory = mock(RecipeAdaptationTaskFactory.class);
    AiTask<RecipeAdaptationResponse> task = mock(AiTask.class);
    when(factory.build(any(), any())).thenReturn(task);
    when(aiService.execute(task)).thenThrow(new AiUnavailableException("503"));

    AdaptationLlmInvoker invoker = new AdaptationLlmInvoker(aiService, factory);
    assertThatThrownBy(() -> invoker.invoke(stubJob(), stubContext()))
        .isInstanceOf(AdaptationAiUnavailableException.class)
        .hasMessageContaining("ai-unavailable");
  }

  @Test
  @SuppressWarnings("unchecked")
  void happy_path_returns_response_from_dispatch() {
    AiService aiService = mock(AiService.class);
    RecipeAdaptationTaskFactory factory = mock(RecipeAdaptationTaskFactory.class);
    AiTask<RecipeAdaptationResponse> task = mock(AiTask.class);
    when(factory.build(any(), any())).thenReturn(task);
    RecipeAdaptationResponse response =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "ok",
            "",
            BigDecimal.valueOf(0.8),
            BigDecimal.valueOf(0.8),
            null,
            null,
            List.of());
    when(aiService.execute(task)).thenReturn(response);

    AdaptationLlmInvoker invoker = new AdaptationLlmInvoker(aiService, factory);
    assertThat(invoker.invoke(stubJob(), stubContext())).isSameAs(response);
  }

  private static AdaptationJob stubJob() {
    return AdaptationJob.builder()
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
  }

  private static AdaptationContext stubContext() {
    return new AdaptationContext(
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
  }
}
