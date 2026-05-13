package com.example.mealprep.feedback.validation;

import com.example.mealprep.feedback.api.dto.UiContextDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implements the screen-aware presence rules declared on {@link ValidUiContext}. Null-tolerant: the
 * {@code @NotNull} on the request-record field guards the null-context case, so a null {@code v} or
 * null {@code screen} here is treated as valid (the other validator surfaces the proper error).
 */
public class UiContextValidator implements ConstraintValidator<ValidUiContext, UiContextDto> {

  @Override
  public boolean isValid(UiContextDto v, ConstraintValidatorContext ctx) {
    if (v == null || v.screen() == null) {
      return true;
    }
    if (v.recipeVersion() != null && v.recipeId() == null) {
      return false;
    }
    return switch (v.screen()) {
      case RECIPE_DETAIL -> v.recipeId() != null;
      case PLAN_MEAL_DETAIL -> v.planId() != null && v.mealSlotId() != null;
      default -> true;
    };
  }
}
