package com.example.mealprep.adaptation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Same-package unit test for {@link AdaptationContextAssembler}'s package-private pure helpers
 * ({@code extractMappingKeys}, {@code mapMode}, {@code hashHardConstraints}). These were flagged by
 * PIT as uncovered survivors; exercising them directly (in-package, no production change) kills the
 * null/blank-filter, dedupe, switch-arm and sentinel-vs-derived mutants.
 */
class AdaptationContextAssemblerHelpersTest {

  private final PreferenceQueryService prefQuery = mock(PreferenceQueryService.class);
  private final AdaptationContextAssembler assembler =
      new AdaptationContextAssembler(
          mock(RecipeQueryService.class), prefQuery, mock(NutritionalKnowledgeService.class));

  @Test
  void extractMappingKeys_null_version_is_empty() {
    assertThat(AdaptationContextAssembler.extractMappingKeys(null)).isEmpty();
  }

  @Test
  void extractMappingKeys_null_ingredients_is_empty() {
    assertThat(AdaptationContextAssembler.extractMappingKeys(version(null))).isEmpty();
  }

  @Test
  void extractMappingKeys_filters_blank_and_null_and_dedupes_preserving_order() {
    RecipeVersionDto v =
        version(List.of(ing("beef"), ing("beef"), ing("  "), ing(null), ing("onion")));
    assertThat(AdaptationContextAssembler.extractMappingKeys(v)).containsExactly("beef", "onion");
  }

  @Test
  void mapMode_covers_every_job_source_arm() {
    assertThat(AdaptationContextAssembler.mapMode(JobSource.IMPORT)).isEqualTo("IMPORT");
    assertThat(AdaptationContextAssembler.mapMode(JobSource.FEEDBACK)).isEqualTo("FEEDBACK");
    assertThat(AdaptationContextAssembler.mapMode(JobSource.DATA_MODEL_CHANGE))
        .isEqualTo("DATA_MODEL_CHANGE");
    assertThat(AdaptationContextAssembler.mapMode(JobSource.PLAN_TIME))
        .isEqualTo("PLAN_TIME_REFINE");
  }

  @Test
  void hashHardConstraints_returns_sentinel_when_absent() {
    UUID userId = UUID.randomUUID();
    when(prefQuery.getHardConstraints(userId)).thenReturn(Optional.empty());
    assertThat(assembler.hashHardConstraints(userId)).isEqualTo("hc:none");
  }

  @Test
  void hashHardConstraints_is_stable_and_non_sentinel_when_present() {
    UUID userId = UUID.randomUUID();
    HardConstraintsDto hc =
        new HardConstraintsDto(
            UUID.randomUUID(),
            userId,
            List.of("peanut"),
            null,
            List.of(),
            List.of(),
            List.of(),
            0L);
    when(prefQuery.getHardConstraints(userId)).thenReturn(Optional.of(hc));

    String h1 = assembler.hashHardConstraints(userId);
    String h2 = assembler.hashHardConstraints(userId);

    assertThat(h1).startsWith("hc:").isNotEqualTo("hc:none").isEqualTo(h2);
  }

  private static IngredientDto ing(String mappingKey) {
    return new IngredientDto(
        UUID.randomUUID(),
        0,
        mappingKey,
        "display",
        BigDecimal.valueOf(100),
        "g",
        "diced",
        false,
        false,
        BigDecimal.valueOf(0.9));
  }

  private static RecipeVersionDto version(List<IngredientDto> ingredients) {
    return new RecipeVersionDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        null,
        VersionTrigger.IMPORT,
        "initial",
        "pending",
        Instant.now(),
        "user:" + UUID.randomUUID(),
        null,
        ingredients,
        List.of(),
        new RecipeMetadataDto(2, 10, 20, 30, List.of("pot"), 3, 4, true, "italian", List.of("d")),
        new RecipeTagsDto("beef", "stovetop", null, List.of(), List.of()),
        null);
  }
}
