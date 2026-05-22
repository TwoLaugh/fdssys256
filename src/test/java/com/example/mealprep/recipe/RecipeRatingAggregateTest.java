package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.domain.service.internal.RecipeRatingServiceImpl;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the weighted aggregate formula (recipe-02b §5). Weights 40/25/15/20; missing
 * non-taste dimensions coalesce to taste; half-up rounding.
 */
class RecipeRatingAggregateTest {

  @Test
  void detailedRating_blendsFourDimensions() {
    // round(85*0.40 + 70*0.25 + 90*0.15 + 75*0.20) = round(34 + 17.5 + 13.5 + 15) = round(80) = 80
    int aggregate = RecipeRatingServiceImpl.computeAggregate(85, 70, 90, 75);
    assertThat(aggregate).isEqualTo(80);
  }

  @Test
  void oneTapRating_coalescesAllToTaste_yieldsTaste() {
    // round(80*0.40 + 80*0.25 + 80*0.15 + 80*0.20) = 80
    int aggregate = RecipeRatingServiceImpl.computeAggregate(80, null, null, null);
    assertThat(aggregate).isEqualTo(80);
  }

  @Test
  void boundary_tasteZero_yieldsZero() {
    assertThat(RecipeRatingServiceImpl.computeAggregate(0, null, null, null)).isZero();
  }

  @Test
  void boundary_tasteMax_yieldsMax() {
    assertThat(RecipeRatingServiceImpl.computeAggregate(100, null, null, null)).isEqualTo(100);
  }

  @Test
  void partialDimensions_coalesceOnlyMissingOnes() {
    // taste=80, effort=40 supplied; portion+repeat coalesce to 80.
    // round(80*0.40 + 40*0.25 + 80*0.15 + 80*0.20) = round(32 + 10 + 12 + 16) = 70
    int aggregate = RecipeRatingServiceImpl.computeAggregate(80, 40, null, null);
    assertThat(aggregate).isEqualTo(70);
  }

  @Test
  void roundingIsHalfUp() {
    // Construct a blend whose fractional part is exactly .5 -> rounds up.
    // taste=79, effort=80, portion=80, repeat=80:
    // 79*0.40 + 80*0.25 + 80*0.15 + 80*0.20 = 31.6 + 20 + 12 + 16 = 79.6 -> 80
    assertThat(RecipeRatingServiceImpl.computeAggregate(79, 80, 80, 80)).isEqualTo(80);
    // taste=80, effort=78 -> 32 + 19.5 + 12 + 16 = 79.5 -> 80 (half-up)
    assertThat(RecipeRatingServiceImpl.computeAggregate(80, 78, 80, 80)).isEqualTo(80);
  }
}
