package com.example.mealprep.provisions.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level validation for inventory quantities. {@code null} is valid (status-tracked items have
 * no quantity); a present quantity must be non-negative, scale ≤ 3, and magnitude ≤ 1,000,000
 * (catches unit-confusion typos like "1000000" grams for a single item).
 */
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.METHOD,
  ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidQuantityValidator.class)
public @interface ValidQuantity {

  String message() default "quantity must be non-negative, scale ≤ 3, magnitude ≤ 1,000,000";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
