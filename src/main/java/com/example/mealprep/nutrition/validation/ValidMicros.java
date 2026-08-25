package com.example.mealprep.nutrition.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint for a request-side micros JSONB document ({@code
 * IntakeEntryDto.micros}, {@code LogSnackRequest.micros}). Enforces the contract's {@code
 * additionalProperties: number, minimum: 0}: the node must be a JSON object (null allowed) and
 * every value a non-negative number. Bean Validation cannot reach inside a {@code JsonNode}, so
 * this mirrors the module's custom-validator pattern (LLD section Validation).
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MicrosDocumentValidator.class)
public @interface ValidMicros {
  String message() default "micros must be an object of non-negative numbers";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
