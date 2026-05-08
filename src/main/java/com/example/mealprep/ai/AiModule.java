package com.example.mealprep.ai;

import com.example.mealprep.ai.domain.service.AiService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the AI module's public service interface. Cross-module callers inject
 * this (or {@link AiService} directly) rather than reaching into {@code domain.service.*}.
 *
 * <p>Mirrors {@code AuthModule}, {@code CoreModule}, {@code PreferenceModule}; thin and carries no
 * business logic. Future tickets add {@code AiCostTrackingService} and {@code
 * PromptTemplateService} accessors as those land.
 */
@Component
public class AiModule {

  private final AiService aiService;

  public AiModule(AiService aiService) {
    this.aiService = aiService;
  }

  public AiService dispatcher() {
    return aiService;
  }
}
