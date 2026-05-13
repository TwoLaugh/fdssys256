package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code @ValidUiContext} class-level validator. Drives the standalone Jakarta
 * validator — no Spring context, no DB. Exercises every branch of the LLD §Validation line 642
 * rules.
 */
class UiContextValidatorTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @Test
  void recipeDetail_withRecipeId_isValid() {
    UiContextDto v = new UiContextDto(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null);
    assertThat(validator.validate(v)).isEmpty();
  }

  @Test
  void recipeDetail_withoutRecipeId_isInvalid() {
    UiContextDto v = new UiContextDto(Screen.RECIPE_DETAIL, null, null, null, null, null);
    Set<ConstraintViolation<UiContextDto>> violations = validator.validate(v);
    assertThat(violations).isNotEmpty();
  }

  @Test
  void planMealDetail_withPlanIdAndMealSlotId_isValid() {
    UiContextDto v =
        new UiContextDto(
            Screen.PLAN_MEAL_DETAIL, null, null, UUID.randomUUID(), UUID.randomUUID(), null);
    assertThat(validator.validate(v)).isEmpty();
  }

  @Test
  void planMealDetail_missingPlanId_isInvalid() {
    UiContextDto v =
        new UiContextDto(Screen.PLAN_MEAL_DETAIL, null, null, UUID.randomUUID(), null, null);
    assertThat(validator.validate(v)).isNotEmpty();
  }

  @Test
  void planMealDetail_missingMealSlotId_isInvalid() {
    UiContextDto v =
        new UiContextDto(Screen.PLAN_MEAL_DETAIL, null, null, null, UUID.randomUUID(), null);
    assertThat(validator.validate(v)).isNotEmpty();
  }

  @Test
  void general_withAllFieldsNull_isValid() {
    UiContextDto v = new UiContextDto(Screen.GENERAL, null, null, null, null, null);
    assertThat(validator.validate(v)).isEmpty();
  }

  @Test
  void settings_withAllFieldsNull_isValid() {
    UiContextDto v = new UiContextDto(Screen.SETTINGS, null, null, null, null, null);
    assertThat(validator.validate(v)).isEmpty();
  }

  @Test
  void recipeVersionWithoutRecipeId_isInvalid() {
    UiContextDto v = new UiContextDto(Screen.GENERAL, null, 3, null, null, null);
    assertThat(validator.validate(v)).isNotEmpty();
  }

  @Test
  void recipeVersionWithRecipeId_isValid() {
    UiContextDto v = new UiContextDto(Screen.GENERAL, UUID.randomUUID(), 3, null, null, null);
    assertThat(validator.validate(v)).isEmpty();
  }

  @Test
  void nullScreen_isTreatedAsValid_byOurValidator() {
    // The class-level validator is lenient on null screen — the @NotNull on UiContextDto in the
    // request DTO surfaces the proper field-level error. We just don't want a NullPointerException.
    UiContextDto v = new UiContextDto(null, null, null, null, null, null);
    assertThat(validator.validate(v)).isEmpty();
  }
}
