package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.RecipeAdaptationTaskFactory;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.exception.AdaptationAiResponseInvalidException;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import org.springframework.stereotype.Component;

/**
 * Stage-C dispatcher: builds the {@code RecipeAdaptationTask} via the injected factory and
 * dispatches via {@link AiService#execute}. Translates EVERY {@code ai.exception.*} (the {@link
 * AiException} hierarchy) into an adaptation-module exception so the orchestrator's adaptation-only
 * catch blocks can terminalise the job — nothing from the AI module escapes raw.
 *
 * <p>Mapping (per ticket §Fix 1):
 *
 * <ul>
 *   <li>{@link AiUnavailableException} (upstream 5xx / network) and {@link
 *       AiCostBudgetExceededException} (rolling cost cap — a rate concept) are <b>deferrable</b> →
 *       {@link AdaptationAiUnavailableException} → {@code AI_UNAVAILABLE}.
 *   <li>Everything else (notably {@code AiInvalidResponseException} — malformed model output — and
 *       {@code AiInvalidRequestException}) is <b>terminal</b> → {@link
 *       AdaptationAiResponseInvalidException} → {@code LLM_ERROR}.
 * </ul>
 *
 * <p>Per ticket 01c §Step 5; LLD §Stage C lines 746-747.
 */
@Component
public class AdaptationLlmInvoker {

  private final AiService aiService;
  private final RecipeAdaptationTaskFactory taskFactory;

  public AdaptationLlmInvoker(AiService aiService, RecipeAdaptationTaskFactory taskFactory) {
    this.aiService = aiService;
    this.taskFactory = taskFactory;
  }

  /** Build the task and dispatch. */
  public RecipeAdaptationResponse invoke(AdaptationJob job, AdaptationContext context) {
    AiTask<RecipeAdaptationResponse> task = taskFactory.build(job, context);
    try {
      return aiService.execute(task);
    } catch (AiUnavailableException | AiCostBudgetExceededException e) {
      // Deferrable: upstream-down or cost-cap. Graceful-degrade signal, not a permanent failure.
      throw new AdaptationAiUnavailableException("ai-unavailable: " + e.getMessage(), e);
    } catch (AiException e) {
      // Terminal: malformed/unparseable model output or a 4xx caller-bug. Never retried as-is.
      throw new AdaptationAiResponseInvalidException("ai-invalid-response: " + e.getMessage(), e);
    }
  }
}
