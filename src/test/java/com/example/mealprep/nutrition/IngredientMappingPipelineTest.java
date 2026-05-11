package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.config.OffSearchResultDto;
import com.example.mealprep.nutrition.config.OpenFoodFactsClient;
import com.example.mealprep.nutrition.config.UsdaApiClient;
import com.example.mealprep.nutrition.config.UsdaSearchResultDto;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.service.internal.IngredientLookupInput;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMappingPipeline;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMappingResult;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link IngredientMappingPipeline} — no Spring context, all dependencies mocked.
 */
class IngredientMappingPipelineTest {

  private IngredientMappingRepository repo;
  private UsdaApiClient usda;
  private OpenFoodFactsClient off;
  private IngredientMappingPipeline pipeline;
  private IngredientMappingMapper mapper;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(IngredientMappingRepository.class);
    usda = Mockito.mock(UsdaApiClient.class);
    off = Mockito.mock(OpenFoodFactsClient.class);
    mapper = new IngredientMappingMapper() {};
    pipeline = new IngredientMappingPipeline(repo, new IntakeKeyNormaliser(), usda, off, mapper);
  }

  @Test
  void cache_hit_short_circuits_no_external_call() {
    IngredientMapping row =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.8);
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.of(row));

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("  Chicken Breast  ", null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Resolved.class);
    verify(usda, never()).search(anyString());
    verify(off, never()).search(anyString());
  }

  @Test
  void usda_hit_persists_and_returns_resolved() {
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.empty());
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(
            List.of(new UsdaSearchResultDto.Food(12345, "Chicken Breast", 0.9, List.of())));
    when(usda.search("chicken breast")).thenReturn(Optional.of(usdaDto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("Chicken Breast", null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Resolved.class);
    IngredientMappingResult.Resolved resolved = (IngredientMappingResult.Resolved) result;
    assertThat(resolved.dto().source()).isEqualTo(IngredientMappingSource.USDA);
    assertThat(resolved.dto().confidence().doubleValue()).isEqualTo(0.85); // capped at 0.85
    verify(repo, times(1)).saveAndFlush(any(IngredientMapping.class));
    verify(off, never()).search(anyString());
  }

  @Test
  void usda_empty_falls_back_to_off() {
    when(repo.findBySearchTerm("banana")).thenReturn(Optional.empty());
    when(usda.search("banana")).thenReturn(Optional.empty());
    OffSearchResultDto offDto =
        new OffSearchResultDto(List.of(new OffSearchResultDto.Product("0001", "Banana", null)));
    when(off.search("banana")).thenReturn(Optional.of(offDto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(inv -> inv.getArgument(0));

    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("banana", null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Resolved.class);
    IngredientMappingResult.Resolved resolved = (IngredientMappingResult.Resolved) result;
    assertThat(resolved.dto().source()).isEqualTo(IngredientMappingSource.OPEN_FOOD_FACTS);
    // OFF default score is 0.6 → needsReview = true
    assertThat(resolved.dto().needsReview()).isTrue();
  }

  @Test
  void both_empty_returns_unmapped() {
    when(repo.findBySearchTerm("xyz")).thenReturn(Optional.empty());
    when(usda.search("xyz")).thenReturn(Optional.empty());
    when(off.search("xyz")).thenReturn(Optional.empty());

    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("xyz", null));
    assertThat(result).isInstanceOf(IngredientMappingResult.Unmapped.class);
  }

  @Test
  void race_on_persist_re_reads_winner() {
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.empty());
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(
            List.of(new UsdaSearchResultDto.Food(12345, "Chicken Breast", 0.9, List.of())));
    when(usda.search("chicken breast")).thenReturn(Optional.of(usdaDto));
    when(repo.saveAndFlush(any(IngredientMapping.class)))
        .thenThrow(new DataIntegrityViolationException("uq collision"));
    IngredientMapping winner =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.85);
    when(repo.findBySearchTerm("chicken breast"))
        .thenReturn(Optional.empty()) // first lookup
        .thenReturn(Optional.of(winner)); // re-read after race

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("chicken breast", null));
    assertThat(result).isInstanceOf(IngredientMappingResult.Resolved.class);
  }
}
