package com.example.mealprep.provisions.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/** Implementation of {@link ValidQuantity}. */
public class ValidQuantityValidator implements ConstraintValidator<ValidQuantity, BigDecimal> {

  private static final BigDecimal MAX_MAGNITUDE = new BigDecimal("1000000");
  private static final int MAX_SCALE = 3;

  @Override
  public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    if (value.signum() < 0) {
      return false;
    }
    if (value.scale() > MAX_SCALE) {
      return false;
    }
    return value.compareTo(MAX_MAGNITUDE) <= 0;
  }
}
