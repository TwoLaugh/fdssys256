package com.example.mealprep.grocery.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an observed-price value (an {@link Integer} of pence) that must be a sane price. The final
 * rule (per lld/grocery.md line 777): non-negative integer pence, &le; 1,000,000 (catches a
 * &pound;/p mix-up).
 *
 * <p>01a ships this annotation with the final shape so request records compile, but a PERMISSIVE
 * {@link ObservedPriceValidator} (accepts everything, including the optional null) — grocery-01d
 * tightens it.
 */
@Documented
@Constraint(validatedBy = ObservedPriceValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidObservedPrice {

  String message() default "invalid observed price";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
