package com.example.mealprep.ai;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the AI module's public service interfaces. Cross-module callers inject
 * this (or the underlying interfaces directly) rather than reaching into {@code domain.service.*}.
 *
 * <p>Mirrors {@code AuthModule}, {@code CoreModule}, {@code PreferenceModule}; thin and carries no
 * business logic.
 */
@Component
public class AiModule {

  private final AiService aiService;
  private final PromptTemplateService promptTemplateService;

  public AiModule(AiService aiService, PromptTemplateService promptTemplateService) {
    this.aiService = aiService;
    this.promptTemplateService = promptTemplateService;
  }

  public AiService dispatcher() {
    return aiService;
  }

  public PromptTemplateService promptTemplates() {
    return promptTemplateService;
  }
}
