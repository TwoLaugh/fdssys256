package com.example.mealprep.grocery.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a quantity value (a {@link java.math.BigDecimal}) that must be a sane grocery quantity. The
 * final rule (per lld/grocery.md line 776): non-negative, scale &le; 3, magnitude &le; 1,000,000,
 * unit &isin; the canonical unit set.
 *
 * <p>01a ships this annotation with the final shape so request records compile, but a PERMISSIVE
 * {@link QuantityUnitValidator} (accepts everything non-null) — grocery-01d tightens it once Tier-2
 * mark-bought lands.
 */
@Documented
@Constraint(validatedBy = QuantityUnitValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidQuantityUnit {

  String message() default "invalid quantity";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
