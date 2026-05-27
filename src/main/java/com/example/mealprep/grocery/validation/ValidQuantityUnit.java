package com.example.mealprep.grocery.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a quantity value (a {@link java.math.BigDecimal}) or its unit (a {@link String}) that must
 * be a sane grocery quantity. The rule (per lld/grocery.md line 776): non-negative, scale &le; 3,
 * magnitude &le; 1,000,000 on the quantity; unit &isin; the canonical unit set ({@code g, kg, ml,
 * l, items, pt, tsp, tbsp, cup}) on the unit.
 *
 * <p>Two validators are registered: {@link QuantityUnitValidator} (the {@code BigDecimal} numeric
 * half) and {@link QuantityUnitStringValidator} (the {@code String} unit-set half); Jakarta
 * resolves by target type. Annotate the quantity field AND the unit field to enforce both halves.
 * grocery-01d ships the real bodies (01a shipped permissive stubs).
 */
@Documented
@Constraint(validatedBy = {QuantityUnitValidator.class, QuantityUnitStringValidator.class})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidQuantityUnit {

  String message() default "invalid quantity";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
