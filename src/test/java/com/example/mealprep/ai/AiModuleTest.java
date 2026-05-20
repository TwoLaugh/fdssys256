package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit test for {@link AiModule}. Kills both NullReturnVals mutants on the facade methods
 * ({@code dispatcher} / {@code promptTemplates}) — baseline left them uncovered because the façade
 * is normally injected and never instantiated directly under tests.
 */
class AiModuleTest {

  @Test
  void dispatcher_returnsTheInjectedAiService() {
    AiService aiService = mock(AiService.class);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    AiModule module = new AiModule(aiService, templateService);
    assertThat(module.dispatcher()).isSameAs(aiService);
  }

  @Test
  void promptTemplates_returnsTheInjectedTemplateService() {
    AiService aiService = mock(AiService.class);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    AiModule module = new AiModule(aiService, templateService);
    assertThat(module.promptTemplates()).isSameAs(templateService);
  }
}
