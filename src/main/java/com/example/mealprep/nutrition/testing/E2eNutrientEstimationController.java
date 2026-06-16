package com.example.mealprep.nutrition.testing;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.nutrition.domain.service.internal.NutrientEstimationResult;
import com.example.mealprep.nutrition.domain.service.internal.NutrientEstimationTask;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E test-support surface for the real {@link NutrientEstimationTask}. Runs the task through the
 * live {@link AiService} dispatcher and returns the structured estimates verbatim, so a scenario
 * can exercise the estimation seam end-to-end. Under the {@code e2e} profile the AI call is served
 * by {@code TestAiService} from a canned {@code NUTRIENT_ESTIMATION} response; with the OpenAI /
 * Anthropic provider active and a key present it is a real completion. Profile-gated — never
 * registered in prod.
 */
@RestController
@RequestMapping("/test-support/nutrition")
@Profile("e2e")
public class E2eNutrientEstimationController {

  private final AiService aiService;

  public E2eNutrientEstimationController(AiService aiService) {
    this.aiService = aiService;
  }

  @PostMapping(
      path = "/estimate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public NutrientEstimationResult estimate(@RequestBody EstimateRequest req) {
    NutrientEstimationTask task =
        new NutrientEstimationTask(
            req.recipeName(),
            req.ingredients(),
            req.servings() == null ? 1 : req.servings(),
            req.missingKeys(),
            null,
            null);
    return aiService.execute(task);
  }

  /** Request body: the recipe context + the canonical micro keys to estimate. */
  public record EstimateRequest(
      String recipeName, List<String> ingredients, Integer servings, List<String> missingKeys) {}
}
