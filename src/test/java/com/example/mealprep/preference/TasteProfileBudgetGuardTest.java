package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreferences;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.service.internal.TasteProfileBudgetGuard;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link TasteProfileBudgetGuard}: deterministic counts on small / medium /
 * near-budget fixtures, monotonic growth when content is added, and the 2500-token rejection.
 */
class TasteProfileBudgetGuardTest {

  private final TasteProfileBudgetGuard guard =
      new TasteProfileBudgetGuard(
          new ObjectMapper()
              .registerModule(new JavaTimeModule())
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

  @Test
  void estimate_isDeterministic_sameDocSameCount() {
    TasteProfileDocument doc = TasteProfileTestData.populatedDocument(1);
    assertThat(guard.estimate(doc)).isEqualTo(guard.estimate(doc));
  }

  @Test
  void estimate_emptyDoc_isSmallButNonZero() {
    int estimate = guard.estimate(TasteProfileDocument.empty(LocalDate.parse("2026-05-20")));
    assertThat(estimate).isPositive();
    assertThat(estimate).isLessThan(TasteProfileBudgetGuard.MAX_TOKENS);
  }

  @Test
  void estimate_mediumDoc_underBudget() {
    int estimate = guard.estimate(TasteProfileTestData.populatedDocument(1));
    assertThat(estimate).isPositive().isLessThan(TasteProfileBudgetGuard.MAX_TOKENS);
  }

  @Test
  void estimate_addingAnIngredient_raisesEstimate() {
    TasteProfileDocument base = TasteProfileTestData.populatedDocument(1);
    TasteProfileDocument bigger = withExtraFavourites(base, 5);

    assertThat(guard.estimate(bigger)).isGreaterThan(guard.estimate(base));
  }

  @Test
  void enforce_underBudget_returnsEstimate() {
    int estimate = guard.enforce(TasteProfileTestData.populatedDocument(1));
    assertThat(estimate).isPositive();
  }

  @Test
  void enforce_overBudget_throws() {
    // ~150 long ingredient items × ~80 chars each ≫ 2500-token budget.
    TasteProfileDocument huge = withExtraFavourites(TasteProfileTestData.populatedDocument(1), 150);
    assertThat(guard.estimate(huge)).isGreaterThan(TasteProfileBudgetGuard.MAX_TOKENS);

    assertThatThrownBy(() -> guard.enforce(huge))
        .isInstanceOf(
            com.example.mealprep.preference.exception.TasteProfileBudgetExceededException.class);
  }

  private static TasteProfileDocument withExtraFavourites(TasteProfileDocument base, int count) {
    List<IngredientPreference> favourites =
        new ArrayList<>(base.ingredientPreferences().favourites());
    LocalDate today = LocalDate.parse("2026-05-20");
    for (int i = 0; i < count; i++) {
      favourites.add(
          new IngredientPreference(
              "padded-ingredient-name-number-" + i, i, today, IngredientPreferenceSource.FEEDBACK));
    }
    IngredientPreferences ip = base.ingredientPreferences();
    return new TasteProfileDocument(
        base.lastUpdated(),
        base.version(),
        base.basedOnFeedbackCount(),
        base.feedbackCursor(),
        base.softConstraints(),
        base.flavourPreferences(),
        base.texturePreferences(),
        new IngredientPreferences(
            favourites, ip.disliked(), ip.trendingPositive(), ip.trendingNegative()),
        base.cuisinePreferences(),
        base.cookingPreferences(),
        base.portionStyle(),
        base.householdContext(),
        base.recipesToRepeat(),
        base.recipesToAvoid(),
        base.activeExperiments(),
        base.learnedInsights());
  }
}
