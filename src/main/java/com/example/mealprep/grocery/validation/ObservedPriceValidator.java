package com.example.mealprep.grocery.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link ValidObservedPrice} (grocery-01d ships the real body). A sane observed price
 * is a non-negative integer pence value, &le; 1,000,000 (£10,000) — the upper bound catches a £/p
 * mix-up (e.g. £4.50 entered as 450,000 pence). Per lld/grocery.md line 777.
 *
 * <p>{@code null} is accepted — the annotated price fields ({@code boughtPricePence}, {@code
 * totalSpendPence}) are optional; presence (when required) is enforced by a sibling
 * {@code @NotNull}.
 */
public class ObservedPriceValidator implements ConstraintValidator<ValidObservedPrice, Integer> {

  /**
   * Inclusive upper bound (£10,000 in pence) — anything larger is almost certainly a £/p mix-up.
   */
  static final int MAX_PENCE = 1_000_000;

  @Override
  public boolean isValid(Integer value, ConstraintValidatorContext context) {
    if (value == null) {
      return true; // optional field; @NotNull enforces presence where required
    }
    return value >= 0 && value <= MAX_PENCE;
  }
}
