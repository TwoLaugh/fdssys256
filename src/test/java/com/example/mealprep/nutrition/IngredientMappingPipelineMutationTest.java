package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
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
 * Mutation killers for {@link IngredientMappingPipeline}:
 *
 * <ul>
 *   <li>L72 NullReturnVals — the empty/blank search-term short-circuit ({@code Unmapped} with
 *       reason {@code "empty term"}) was never asserted; returning {@code null} survived.
 *   <li>L119 ConditionalsBoundary — {@code needsReview = confidence < 0.7}; the existing test used
 *       USDA score 0.9 (capped 0.85, clearly above) and OFF 0.6 (clearly below), never the exact
 *       0.70 boundary, so {@code <} ↔ {@code <=} survived.
 *   <li>L136 NullReturnVals — the race re-read returns the winner's DTO; the old test only checked
 *       {@code instanceof Resolved}, so a {@code null}-payload DTO survived. Assert the content.
 *   <li>L140 NullReturnVals — the {@code orElseThrow} supplier; if no winner row exists after the
 *       race the pipeline must throw {@code IllegalStateException}.
 * </ul>
 */
class IngredientMappingPipelineMutationTest {

  private IngredientMappingRepository repo;
  private UsdaApiClient usda;
  private OpenFoodFactsClient off;
  private IngredientMappingPipeline pipeline;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(IngredientMappingRepository.class);
    usda = Mockito.mock(UsdaApiClient.class);
    off = Mockito.mock(OpenFoodFactsClient.class);
    IngredientMappingMapper mapper = new IngredientMappingMapper() {};
    pipeline = new IngredientMappingPipeline(repo, new IntakeKeyNormaliser(), usda, off, mapper);
  }

  // ---------------- L72: empty / blank term short-circuit ----------------

  @Test
  void blankTerm_returnsUnmapped_withEmptyTermReason() {
    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("   \t  ", null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Unmapped.class);
    IngredientMappingResult.Unmapped u = (IngredientMappingResult.Unmapped) result;
    assertThat(u.unmapped().reason()).isEqualTo("empty term");
    assertThat(u.unmapped().name()).isEqualTo("   \t  ");
    // No repo / external lookups should happen for a blank term.
    Mockito.verifyNoInteractions(repo, usda, off);
  }

  @Test
  void nullTerm_returnsUnmapped_notNull() {
    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput(null, null));

    assertThat(result).isInstanceOf(IngredientMappingResult.Unmapped.class);
    assertThat(((IngredientMappingResult.Unmapped) result).unmapped().reason())
        .isEqualTo("empty term");
  }

  // ---------------- L119: needsReview boundary at confidence == 0.70 ----------------

  @Test
  void usdaScoreExactly070_needsReviewIsFalse() {
    // confidence = min(0.70, 0.85) = 0.70. needsReview = 0.70 < 0.70 -> false (original).
    // ConditionalsBoundary `<= ` would flip this to true.
    when(repo.findBySearchTerm("oats")).thenReturn(Optional.empty());
    UsdaSearchResultDto dto =
        new UsdaSearchResultDto(
            List.of(new UsdaSearchResultDto.Food(999, "Oats", 0.70, List.of())));
    when(usda.search("oats")).thenReturn(Optional.of(dto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(i -> i.getArgument(0));

    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("oats", null));

    IngredientMappingResult.Resolved r = (IngredientMappingResult.Resolved) result;
    assertThat(r.dto().confidence().doubleValue()).isEqualTo(0.70);
    assertThat(r.dto().needsReview()).isFalse();
  }

  @Test
  void usdaScoreJustBelow070_needsReviewIsTrue() {
    when(repo.findBySearchTerm("oats")).thenReturn(Optional.empty());
    UsdaSearchResultDto dto =
        new UsdaSearchResultDto(
            List.of(new UsdaSearchResultDto.Food(999, "Oats", 0.69, List.of())));
    when(usda.search("oats")).thenReturn(Optional.of(dto));
    when(repo.saveAndFlush(any(IngredientMapping.class))).thenAnswer(i -> i.getArgument(0));

    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput("oats", null));

    IngredientMappingResult.Resolved r = (IngredientMappingResult.Resolved) result;
    assertThat(r.dto().confidence().doubleValue()).isEqualTo(0.69);
    assertThat(r.dto().needsReview()).isTrue();
  }

  // ---------------- L136: race re-read returns the WINNER's content ----------------

  @Test
  void raceLost_reReadReturnsWinnerRowContent_notNull() {
    when(usda.search("chicken breast"))
        .thenReturn(
            Optional.of(
                new UsdaSearchResultDto(
                    List.of(
                        new UsdaSearchResultDto.Food(12345, "Chicken Breast", 0.9, List.of())))));
    IngredientMapping winner =
        NutritionTestData.ingredientMapping(
            "chicken breast", IngredientMappingSource.OPEN_FOOD_FACTS, 0.82);
    when(repo.findBySearchTerm("chicken breast"))
        .thenReturn(Optional.empty()) // initial cache miss
        .thenReturn(Optional.of(winner)); // re-read after the race
    when(repo.saveAndFlush(any(IngredientMapping.class)))
        .thenThrow(new DataIntegrityViolationException("uq collision"));

    IngredientMappingResult result =
        pipeline.resolve(new IngredientLookupInput("chicken breast", null));

    IngredientMappingResult.Resolved r = (IngredientMappingResult.Resolved) result;
    // The mapped DTO must reflect the WINNER row (kills L136 NullReturnVals — a null DTO would
    // NPE here on source()).
    assertThat(r.dto()).isNotNull();
    assertThat(r.dto().source()).isEqualTo(IngredientMappingSource.OPEN_FOOD_FACTS);
    assertThat(r.dto().searchTerm()).isEqualTo("chicken breast");
  }

  // ---------------- L140: orElseThrow supplier when no winner row exists ----------------

  @Test
  void raceLost_butNoWinnerRowFound_throwsIllegalState() {
    when(usda.search("chicken breast"))
        .thenReturn(
            Optional.of(
                new UsdaSearchResultDto(
                    List.of(
                        new UsdaSearchResultDto.Food(12345, "Chicken Breast", 0.9, List.of())))));
    // Cache miss on first lookup, and STILL empty on the post-race re-read -> orElseThrow fires.
    when(repo.findBySearchTerm("chicken breast")).thenReturn(Optional.empty());
    when(repo.saveAndFlush(any(IngredientMapping.class)))
        .thenThrow(new DataIntegrityViolationException("uq collision"));

    assertThatThrownBy(() -> pipeline.resolve(new IngredientLookupInput("chicken breast", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("race lost but no winner row found");
  }
}
