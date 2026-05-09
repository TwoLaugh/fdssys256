package com.example.mealprep.recipe.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validator on the method-step list of a {@code CreateRecipeRequest}: step numbers must
 * be unique and contiguous starting at 1.
 */
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.METHOD,
  ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidMethodStepsValidator.class)
public @interface ValidMethodSteps {

  String message() default "method step numbers must be unique and contiguous starting at 1";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
