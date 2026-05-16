package com.example.mealprep.adaptation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint asserting a recipe-diff JSON payload is structurally sound (non-null fields, {@code
 * baseVersionId} reference if present, ingredient mapping keys are non-blank strings). Heavier
 * semantic checks (catalogue lookup, ingredient-key existence in the catalogue universe) are
 * intentionally deferred to the service-layer {@code @ValidRecipeDiff} re-validation in {@code
 * acceptPendingChange} — the annotation here is a cheap structural guard suitable for the
 * request-body validation phase.
 *
 * <p>Per ticket §step 45.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RecipeDiffValidator.class)
public @interface ValidRecipeDiff {

  String message() default "recipe-diff JSON shape is invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
