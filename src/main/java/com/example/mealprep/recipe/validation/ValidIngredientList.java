package com.example.mealprep.recipe.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validator on the ingredient list of a {@code CreateRecipeRequest}: each {@code
 * lineOrder} must be unique. Empty / null lists are out of scope here — covered by
 * {@code @NotEmpty}.
 */
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.METHOD,
  ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidIngredientListValidator.class)
public @interface ValidIngredientList {

  String message() default "ingredient lineOrder values must be unique";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
