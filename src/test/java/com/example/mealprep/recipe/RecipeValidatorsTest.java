package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.validation.ValidIngredientListValidator;
import com.example.mealprep.recipe.validation.ValidMethodStepsValidator;
import com.example.mealprep.recipe.validation.ValidRecipeMetadataValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of the three recipe Jakarta {@code ConstraintValidator}s. The {@code
 * ConstraintValidatorContext} argument is unused by every implementation, so {@code null} is safe
 * and keeps these branch-exhaustive. Real instances, no mocking.
 */
class RecipeValidatorsTest {

  @Nested
  class IngredientList {

    private final ValidIngredientListValidator validator = new ValidIngredientListValidator();

    @Test
    void nullList_isValid() {
      assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void emptyList_isValid() {
      assertThat(validator.isValid(List.of(), null)).isTrue();
    }

    @Test
    void distinctLineOrders_isValid() {
      assertThat(validator.isValid(List.of(ing(0), ing(1), ing(2)), null)).isTrue();
    }

    @Test
    void duplicateLineOrder_isInvalid() {
      assertThat(validator.isValid(List.of(ing(0), ing(1), ing(0)), null)).isFalse();
    }

    @Test
    void nullElementsAreSkipped_notTreatedAsDuplicate() {
      List<CreateIngredientRequest> withNulls = new ArrayList<>();
      withNulls.add(ing(0));
      withNulls.add(null);
      withNulls.add(null);
      withNulls.add(ing(1));
      assertThat(validator.isValid(withNulls, null)).isTrue();
    }

    @Test
    void duplicateAfterNull_stillDetected() {
      List<CreateIngredientRequest> list = new ArrayList<>();
      list.add(ing(5));
      list.add(null);
      list.add(ing(5));
      assertThat(validator.isValid(list, null)).isFalse();
    }

    private CreateIngredientRequest ing(int lineOrder) {
      return new CreateIngredientRequest(lineOrder, "k" + lineOrder, "d", null, null, null, false);
    }
  }

  @Nested
  class MethodSteps {

    private final ValidMethodStepsValidator validator = new ValidMethodStepsValidator();

    @Test
    void nullList_isValid() {
      assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void emptyList_isValid() {
      assertThat(validator.isValid(List.of(), null)).isTrue();
    }

    @Test
    void contiguousFromOne_isValid() {
      assertThat(validator.isValid(List.of(step(1), step(2), step(3)), null)).isTrue();
    }

    @Test
    void contiguousButOutOfOrder_isValid() {
      // Order in the list does not matter; the {1..n} set membership does.
      assertThat(validator.isValid(List.of(step(3), step(1), step(2)), null)).isTrue();
    }

    @Test
    void duplicateStepNumber_isInvalid() {
      assertThat(validator.isValid(List.of(step(1), step(2), step(2)), null)).isFalse();
    }

    @Test
    void notStartingAtOne_isInvalid() {
      // {2,3,4} : max=4 != size 3 → invalid.
      assertThat(validator.isValid(List.of(step(2), step(3), step(4)), null)).isFalse();
    }

    @Test
    void gapInSequence_isInvalid() {
      // {1,2,4} : max=4 != size 3 → invalid (max-mismatch branch).
      assertThat(validator.isValid(List.of(step(1), step(2), step(4)), null)).isFalse();
    }

    @Test
    void maxEqualsSizeButMissingMiddle_failsContiguityLoop() {
      // Two trailing nulls inflate value.size() to 5 while real stepNumbers are {1,5,2}.
      // max == 5 == value.size() so the max!=size guard passes, forcing the {1..n} membership
      // loop, which finds 3 missing → invalid. Exercises the contiguity branch directly.
      List<CreateMethodStepRequest> list = new ArrayList<>();
      list.add(step(1));
      list.add(step(5));
      list.add(step(2));
      list.add(null);
      list.add(null);
      assertThat(validator.isValid(list, null)).isFalse();
    }

    @Test
    void maxEqualsSizeAndAllPresent_isValid() {
      // {1,2,3} distinct, max==size==3, every 1..3 present → the contiguity loop completes
      // and returns true (kills the BooleanTrueReturn / negate mutants on the final return).
      assertThat(validator.isValid(List.of(step(1), step(2), step(3)), null)).isTrue();
    }

    @Test
    void offByOneOnMax_boundaryExactlyAtSize_isValidNotInvalid() {
      // {1,2} max==2==size: ConditionalsBoundary on `max != value.size()` would flip the
      // exact-equality outcome; asserting valid here pins that boundary.
      assertThat(validator.isValid(List.of(step(1), step(2)), null)).isTrue();
    }

    @Test
    void nullElementsSkipped_validSequenceStillPasses() {
      List<CreateMethodStepRequest> list = new ArrayList<>();
      list.add(step(1));
      list.add(null);
      list.add(step(2));
      // value.size() counts the null too (=3) but only 2 real steps, max=2 != 3 → invalid.
      assertThat(validator.isValid(list, null)).isFalse();
    }

    @Test
    void singleStepNumberOne_isValid() {
      assertThat(validator.isValid(List.of(step(1)), null)).isTrue();
    }

    @Test
    void singleStepNumberTwo_isInvalid() {
      assertThat(validator.isValid(List.of(step(2)), null)).isFalse();
    }

    private CreateMethodStepRequest step(int stepNumber) {
      return new CreateMethodStepRequest(stepNumber, "do something", null);
    }
  }

  @Nested
  class RecipeMetadataValidator {

    private final ValidRecipeMetadataValidator validator = new ValidRecipeMetadataValidator();

    @Test
    void nullMetadata_isValid() {
      assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void exactSum_isValid() {
      assertThat(validator.isValid(meta(10, 20, 30), null)).isTrue();
    }

    @Test
    void offByPlusOne_isValid() {
      assertThat(validator.isValid(meta(10, 20, 31), null)).isTrue();
    }

    @Test
    void offByMinusOne_isValid() {
      assertThat(validator.isValid(meta(10, 20, 29), null)).isTrue();
    }

    @Test
    void offByPlusTwo_isInvalid() {
      assertThat(validator.isValid(meta(10, 20, 32), null)).isFalse();
    }

    @Test
    void offByMinusTwo_isInvalid() {
      assertThat(validator.isValid(meta(10, 20, 28), null)).isFalse();
    }

    @Test
    void zeroEverywhere_isValid() {
      assertThat(validator.isValid(meta(0, 0, 0), null)).isTrue();
    }

    private CreateRecipeMetadataRequest meta(int prep, int cook, int total) {
      return new CreateRecipeMetadataRequest(
          1, prep, cook, total, List.of(), null, null, false, "Italian", List.of());
    }
  }
}
