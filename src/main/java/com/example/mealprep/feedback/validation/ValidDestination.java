package com.example.mealprep.feedback.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level guard on {@code CorrectionRequest.newDestination} (lld/feedback.md §Validation line
 * 643; ticket 01f §2).
 *
 * <p>Deliberately a simple value-presence guard: {@code newDestination} must be a recognised {@link
 * com.example.mealprep.feedback.spi.Destination} enum value. Jakarta's enum binding already rejects
 * unknown strings with 400; this annotation documents intent and rejects null. The
 * <em>structural</em> check ("correcting to RECIPE needs a recipeId somewhere") is a routing-row
 * concern done in the service layer as an {@code InvalidCorrectionTargetException} (422) — it
 * cannot be expressed at Bean-Validation scope without the loaded routing log.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidDestinationValidator.class)
public @interface ValidDestination {

  String message() default "destination is not a recognised value";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
