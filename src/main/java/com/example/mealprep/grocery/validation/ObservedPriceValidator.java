package com.example.mealprep.grocery.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * PERMISSIVE validator for {@link ValidObservedPrice}. 01a accepts any value, including null (the
 * annotated price fields are optional). grocery-01d replaces the body with the real rule
 * (non-negative integer pence, &le; 1,000,000) per lld/grocery.md line 777.
 */
public class ObservedPriceValidator implements ConstraintValidator<ValidObservedPrice, Integer> {

  @Override
  public boolean isValid(Integer value, ConstraintValidatorContext context) {
    // 01a: permissive. grocery-01d tightens.
    return true;
  }
}
