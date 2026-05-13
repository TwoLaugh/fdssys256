package com.example.mealprep.feedback.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint enforcing screen-aware presence rules on {@code UiContextDto}. Per LLD
 * §Validation line 642 (lld/feedback.md):
 *
 * <ul>
 *   <li>{@code screen == RECIPE_DETAIL} → {@code recipeId} non-null
 *   <li>{@code screen == PLAN_MEAL_DETAIL} → {@code planId} non-null AND {@code mealSlotId}
 *       non-null
 *   <li>{@code recipeVersion != null} implies {@code recipeId != null}
 * </ul>
 *
 * <p>Other screens have no field requirements.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UiContextValidator.class)
public @interface ValidUiContext {

  String message() default "ui context inconsistent with screen";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
