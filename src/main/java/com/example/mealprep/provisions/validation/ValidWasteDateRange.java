package com.example.mealprep.provisions.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level validation enforcing {@code from <= to} on a waste-list / waste-summary query. Picked
 * the Jakarta-level path so the 400 surfaces consistently through the validation pipeline; a
 * service-side check would land as a 422 by default which is less useful for query-param errors.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WasteDateRangeValidator.class)
public @interface ValidWasteDateRange {

  String message() default "from must be on or before to";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
