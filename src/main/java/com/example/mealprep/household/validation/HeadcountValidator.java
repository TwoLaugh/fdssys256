package com.example.mealprep.household.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidHeadcount} implementation. {@code null} is valid (optional field); otherwise the
 * value must be in {@code [ValidHeadcount.MIN, ValidHeadcount.MAX]}.
 */
public class HeadcountValidator implements ConstraintValidator<ValidHeadcount, Integer> {

  @Override
  public boolean isValid(Integer value, ConstraintValidatorContext ctx) {
    if (value == null) {
      return true;
    }
    return value >= ValidHeadcount.MIN && value <= ValidHeadcount.MAX;
  }
}
