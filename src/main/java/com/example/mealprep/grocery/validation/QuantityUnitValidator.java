package com.example.mealprep.grocery.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * PERMISSIVE validator for {@link ValidQuantityUnit}. 01a accepts any value (including null —
 * presence is enforced by a sibling {@code @NotNull}); grocery-01d replaces the body with the real
 * rule (non-negative, scale &le; 3, magnitude &le; 1,000,000) per lld/grocery.md line 776.
 */
public class QuantityUnitValidator implements ConstraintValidator<ValidQuantityUnit, BigDecimal> {

  @Override
  public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
    // 01a: permissive. grocery-01d tightens.
    return true;
  }
}
