package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import com.example.mealprep.ai.domain.service.internal.PromptTemplateLoader;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the recipe-adaptation prompt template parses and round-trips through the {@code
 * ai_prompt_template} table + {@link PromptTemplateService} (the path {@code
 * RecipeAdaptationTaskFactoryImpl}'s {@code PromptRef("RecipeAdaptationTask", 1)} resolves against
 * at runtime).
 *
 * <p><b>Test-classpath shadowing note</b> (wave-2/3 gotcha): under {@code @SpringBootTest} the
 * loader's default {@code classpath:prompts/*.md} resolves against {@code target/test-classes
 * /prompts/} first, which carries only the {@code test-template.md} fixture — the real {@code
 * lld/prompts/*.md} (copied to {@code target/classes/prompts/} by the pom resource block) is
 * shadowed, so the {@code @PostConstruct} startup load never inserts {@code RecipeAdaptationTask}
 * in tests. This IT therefore drives the loader explicitly against the real {@code lld/prompts}
 * source directory (the same content CI's production classpath sees) and asserts the row resolves.
 * The runtime stub file shipping on the classpath is asserted separately.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationPromptTemplateLoadIT {

  @Autowired private PromptTemplateService promptTemplateService;
  @Autowired private com.example.mealprep.ai.domain.repository.PromptTemplateRepository repository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @Test
  void recipe_adaptation_template_parses_loads_and_resolves_via_service() {
    Path lldPrompts = Path.of(System.getProperty("user.dir"), "lld", "prompts");
    PromptTemplateLoader loader =
        new PromptTemplateLoader(
            repository,
            eventPublisher,
            java.time.Clock.systemUTC(),
            "file:" + lldPrompts.toAbsolutePath() + "/");

    int inserted = loader.loadAll();
    assertThat(inserted).isPositive();

    PromptTemplateDto dto = promptTemplateService.get("RecipeAdaptationTask", 1);
    assertThat(dto.name()).isEqualTo("RecipeAdaptationTask");
    assertThat(dto.version()).isEqualTo(1);
    assertThat(dto.systemPrompt()).isNotBlank();
    assertThat(dto.modelTier()).isEqualTo(com.example.mealprep.ai.spi.ModelTier.MID);

    // Second load is idempotent (same source_hash) — latest stays version 1.
    int secondPass = loader.loadAll();
    assertThat(secondPass).isZero();
    assertThat(promptTemplateService.getLatest("RecipeAdaptationTask").version()).isEqualTo(1);
  }

  @Test
  void runtime_prompt_stub_file_is_on_classpath() {
    var resource =
        new org.springframework.core.io.ClassPathResource(
            "prompts/adaptation/recipe-adaptation.txt");
    assertThat(resource.exists())
        .as("prompts/adaptation/recipe-adaptation.txt must ship on the classpath")
        .isTrue();
  }
}
