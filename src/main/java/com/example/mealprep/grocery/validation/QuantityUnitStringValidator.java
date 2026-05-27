package com.example.mealprep.grocery.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

/**
 * Unit-set validator for {@link ValidQuantityUnit} (grocery-01d). Enforces the canonical-unit-set
 * half of the rule on a {@code String} unit field: {@code unit ∈ {g, kg, ml, l, items, pt, tsp,
 * tbsp, cup}}. Per lld/grocery.md line 776. The companion {@link QuantityUnitValidator} enforces
 * the numeric half on the sibling quantity {@code BigDecimal}.
 *
 * <p>The match is case-insensitive on the trimmed value. {@code null} is accepted — presence (when
 * required) is enforced by a sibling {@code @NotNull}.
 */
public class QuantityUnitStringValidator implements ConstraintValidator<ValidQuantityUnit, String> {

  /** The canonical grocery unit set (lld/grocery.md line 776). */
  static final Set<String> CANONICAL_UNITS =
      Set.of("g", "kg", "ml", "l", "items", "pt", "tsp", "tbsp", "cup");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true; // optional; @NotNull enforces presence where required
    }
    return CANONICAL_UNITS.contains(value.trim().toLowerCase(java.util.Locale.ROOT));
  }
}
