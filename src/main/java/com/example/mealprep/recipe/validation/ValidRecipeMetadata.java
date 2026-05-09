package com.example.mealprep.recipe.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validator on {@code CreateRecipeMetadataRequest}: {@code totalTimeMins} must equal
 * {@code prepTimeMins + cookTimeMins} (within ±1 to allow rounding).
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidRecipeMetadataValidator.class)
public @interface ValidRecipeMetadata {

  String message() default "totalTimeMins must equal prepTimeMins + cookTimeMins (±1)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
