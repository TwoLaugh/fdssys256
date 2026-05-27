package com.example.mealprep.grocery.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * Quantity-value validator for {@link ValidQuantityUnit} (grocery-01d ships the real body). A sane
 * grocery quantity is non-negative, has scale &le; 3 (millilitre / gram precision), and magnitude
 * &le; 1,000,000. Per lld/grocery.md line 776.
 *
 * <p>The companion {@link QuantityUnitStringValidator} enforces the canonical-unit-set half of the
 * rule on the sibling unit {@code String} field. Both are registered on {@link ValidQuantityUnit}
 * and resolved by Jakarta by target type.
 *
 * <p>{@code null} is accepted — presence (when required) is enforced by a sibling {@code @NotNull}.
 */
public class QuantityUnitValidator implements ConstraintValidator<ValidQuantityUnit, BigDecimal> {

  /** Inclusive magnitude bound — no single grocery line is sanely larger than a million units. */
  static final BigDecimal MAX_MAGNITUDE = BigDecimal.valueOf(1_000_000L);

  /** Maximum decimal scale — millilitre / gram precision is enough; deeper is a data-entry slip. */
  static final int MAX_SCALE = 3;

  @Override
  public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
    if (value == null) {
      return true; // optional; @NotNull enforces presence where required
    }
    if (value.signum() < 0) {
      return false; // non-negative
    }
    if (value.stripTrailingZeros().scale() > MAX_SCALE) {
      return false; // scale ≤ 3 (ignoring trailing zeros so 1.000 == 1)
    }
    return value.abs().compareTo(MAX_MAGNITUDE) <= 0; // magnitude ≤ 1,000,000
  }
}
