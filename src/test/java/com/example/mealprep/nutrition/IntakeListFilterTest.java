package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.IntakeListFilter;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link IntakeListFilter}'s {@code hasQuery()} short-circuit. The service layer
 * relies on this to decide whether to apply the {@code LIKE} predicate; an off-by-one here would
 * either always-apply (excluding non-matching rows) or never-apply (including them all). Per ticket
 * {@code infra/01b}.
 */
class IntakeListFilterTest {

  @Test
  void hasQuery_returnsFalse_whenQIsNull() {
    IntakeListFilter filter = new IntakeListFilter(null, null, null);
    assertThat(filter.hasQuery()).isFalse();
  }

  @Test
  void hasQuery_returnsFalse_whenQIsEmptyString() {
    IntakeListFilter filter = new IntakeListFilter(null, null, "");
    assertThat(filter.hasQuery()).isFalse();
  }

  @Test
  void hasQuery_returnsFalse_whenQIsBlank() {
    IntakeListFilter filter = new IntakeListFilter(null, null, "   ");
    assertThat(filter.hasQuery()).isFalse();
  }

  @Test
  void hasQuery_returnsTrue_whenQHasContent() {
    IntakeListFilter filter = new IntakeListFilter(null, null, "chicken");
    assertThat(filter.hasQuery()).isTrue();
  }

  @Test
  void hasQuery_returnsTrue_whenQHasMixedWhitespace() {
    // " chicken " is non-blank → applies the filter; the JPQL-side LIKE trims via lower(concat...).
    IntakeListFilter filter = new IntakeListFilter(null, null, " chicken ");
    assertThat(filter.hasQuery()).isTrue();
  }

  @Test
  void recordExposesAllThreeFields() {
    UUID recipeId = UUID.randomUUID();
    IntakeListFilter filter = new IntakeListFilter(recipeId, MealSlot.DINNER, "salad");
    assertThat(filter.plannedRecipeId()).isEqualTo(recipeId);
    assertThat(filter.mealSlot()).isEqualTo(MealSlot.DINNER);
    assertThat(filter.q()).isEqualTo("salad");
  }
}
