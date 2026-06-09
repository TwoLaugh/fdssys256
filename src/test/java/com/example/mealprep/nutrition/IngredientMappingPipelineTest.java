package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
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
import com.example.mealprep.nutrition.domain.service.internal.IngredientMatchResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseResult;
import com.example.mealprep.nutrition.domain.service.internal.IngredientParseTask;
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
  private AiService aiService;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(IngredientMappingRepository.class);
    usda = Mockito.mock(UsdaApiClient.class);
    off = Mockito.mock(OpenFoodFactsClient.class);
    mapper = new IngredientMappingMapper() {};
    // Default: AI dispatch FAILS, so every test here exercises the deterministic fallback safety
    // net (parse -> normalised term verbatim; match -> first-hit, capped at 0.85). Tests that need
    // the AI to succeed override this stub locally.
    aiService = Mockito.mock(AiService.class);
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiUnavailableException("stubbed AI outage"));
    pipeline =
        new IngredientMappingPipeline(
            repo, new IntakeKeyNormaliser(), usda, off, mapper, aiService);
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

  // ---------------- AI parse + match wiring (nutrition-01k) ----------------

  @Test
  void aiParse_cleanTerm_drivesUsdaSearch() {
    // AI parse returns a cleaned term different from the normalised line; the USDA search must use
    // the AI term, not the normalised one.
    when(aiService.execute(any(AiTask.class)))
        .thenAnswer(
            inv -> {
              AiTask<?> task = inv.getArgument(0);
              if (task instanceof IngredientParseTask) {
                return new IngredientParseResult(
                    "chicken breast",
                    "chicken breast",
                    null,
                    null,
                    null,
                    false,
                    new java.math.BigDecimal("0.95"));
              }
              // match declines -> first-hit fallback
              return new IngredientMatchResult(-1, java.math.BigDecimal.ZERO, "none");
            });
    when(repo.findBySearchTerm("2 chicken breasts, diced")).thenReturn(Optional.empty());
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(
            List.of(new UsdaSearchResultDto.Food(12345, "Chicken Breast", 0.9, List.of())));
    when(usda.search("chicken breast")).thenReturn(Optional.of(usdaDto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(i -> i.getArgument(0));

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("2 chicken breasts, diced", null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Resolved.class);
    // The USDA search was driven by the AI-cleaned term; the normalised line was never searched.
    verify(usda, times(1)).search("chicken breast");
    verify(usda, never()).search("2 chicken breasts, diced");
  }

  @Test
  void aiMatch_picksCandidate_liftsConfidenceCap() {
    // The model picks the SECOND candidate with a high confidence; the 0.85 cap lifts (LLD 982).
    when(aiService.execute(any(AiTask.class)))
        .thenAnswer(
            inv -> {
              AiTask<?> task = inv.getArgument(0);
              if (task instanceof IngredientParseTask) {
                // parse declines a usable term -> pipeline uses the normalised term to search
                return new IngredientParseResult(
                    null, null, null, null, null, false, new java.math.BigDecimal("0.5"));
              }
              return new IngredientMatchResult(
                  1, new java.math.BigDecimal("0.97"), "best raw match");
            });
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.empty());
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(
            List.of(
                new UsdaSearchResultDto.Food(111, "Chicken breast, breaded", 0.95, List.of()),
                new UsdaSearchResultDto.Food(222, "Chicken breast, raw", 0.7, List.of())));
    when(usda.search("chicken breast")).thenReturn(Optional.of(usdaDto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(i -> i.getArgument(0));

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("chicken breast", null));

    IngredientMappingResult.Resolved resolved = (IngredientMappingResult.Resolved) result;
    // Cap lifted: the model's 0.97 confidence is used, not the 0.85 fallback cap.
    assertThat(resolved.dto().confidence().doubleValue()).isEqualTo(0.97);
    // The chosen candidate is index 1 (fdcId 222), not the highest-score first hit.
    assertThat(resolved.dto().externalId()).isEqualTo("222");
    assertThat(resolved.dto().needsReview()).isFalse();
  }

  @Test
  void aiMatch_outOfRangeIndex_degradesToFirstHit() {
    when(aiService.execute(any(AiTask.class)))
        .thenAnswer(
            inv -> {
              AiTask<?> task = inv.getArgument(0);
              if (task instanceof IngredientParseTask) {
                return new IngredientParseResult(
                    null, null, null, null, null, false, new java.math.BigDecimal("0.5"));
              }
              // Index 9 is out of range for a 1-element list -> fall back to first hit, capped.
              return new IngredientMatchResult(9, new java.math.BigDecimal("0.99"), "bogus");
            });
    when(repo.findBySearchTerm("banana")).thenReturn(Optional.empty());
    UsdaSearchResultDto usdaDto =
        new UsdaSearchResultDto(List.of(new UsdaSearchResultDto.Food(7, "Banana", 0.9, List.of())));
    when(usda.search("banana")).thenReturn(Optional.of(usdaDto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(i -> i.getArgument(0));

    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("banana", null));

    IngredientMappingResult.Resolved resolved = (IngredientMappingResult.Resolved) result;
    // Out-of-range pick is rejected -> deterministic first hit, capped at 0.85.
    assertThat(resolved.dto().confidence().doubleValue()).isEqualTo(0.85);
    assertThat(resolved.dto().externalId()).isEqualTo("7");
  }

  @Test
  void cache_hit_makes_no_ai_call() {
    IngredientMapping row =
        NutritionTestData.ingredientMapping("chicken breast", IngredientMappingSource.USDA, 0.8);
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.of(row));

    pipeline.resolve(new IngredientLookupInput("Chicken Breast", null));

    // The cache-check short-circuits BEFORE any AI dispatch — repeats are free.
    verify(aiService, never()).execute(any(AiTask.class));
  }
}
