package com.example.mealprep.grocery.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the two {@link ValidQuantityUnit} validators (grocery-01d): the numeric {@link
 * QuantityUnitValidator} (non-negative, scale &le; 3, magnitude &le; 1,000,000) and the unit-set
 * {@link QuantityUnitStringValidator} ({@code unit ∈ {g, kg, ml, l, items, pt, tsp, tbsp, cup}}).
 * Per lld/grocery.md line 776. Pure unit test — the context is unused by the bodies.
 */
class QuantityUnitValidatorTest {

  private final QuantityUnitValidator quantity = new QuantityUnitValidator();
  private final QuantityUnitStringValidator unit = new QuantityUnitStringValidator();

  private boolean validQuantity(BigDecimal value) {
    return quantity.isValid(value, null);
  }

  private boolean validUnit(String value) {
    return unit.isValid(value, null);
  }

  // ---------------- quantity (BigDecimal) ----------------

  @Test
  void quantity_null_isAccepted() {
    assertThat(validQuantity(null)).isTrue();
  }

  @Test
  void quantity_zero_isAccepted() {
    assertThat(validQuantity(BigDecimal.ZERO)).isTrue();
  }

  @Test
  void quantity_typicalThreeDecimalPlaces_isAccepted() {
    assertThat(validQuantity(new BigDecimal("1.250"))).isTrue();
  }

  @Test
  void quantity_trailingZerosDoNotInflateScale() {
    // 1.0000 has nominal scale 4 but strips to scale 0 — accepted.
    assertThat(validQuantity(new BigDecimal("1.0000"))).isTrue();
  }

  @Test
  void quantity_upperMagnitude_isAccepted_inclusive() {
    assertThat(validQuantity(new BigDecimal("1000000"))).isTrue();
  }

  @Test
  void quantity_negative_isRejected() {
    assertThat(validQuantity(new BigDecimal("-0.001"))).isFalse();
  }

  @Test
  void quantity_scaleAboveThree_isRejected() {
    assertThat(validQuantity(new BigDecimal("1.2345"))).isFalse();
  }

  @Test
  void quantity_aboveMagnitude_isRejected() {
    assertThat(validQuantity(new BigDecimal("1000000.001"))).isFalse();
  }

  // ---------------- unit (String) ----------------

  @Test
  void unit_null_isAccepted() {
    assertThat(validUnit(null)).isTrue();
  }

  @Test
  void unit_canonicalSet_allAccepted() {
    for (String u : new String[] {"g", "kg", "ml", "l", "items", "pt", "tsp", "tbsp", "cup"}) {
      assertThat(validUnit(u)).as("unit %s", u).isTrue();
    }
  }

  @Test
  void unit_caseInsensitiveAndTrimmed() {
    assertThat(validUnit("  KG ")).isTrue();
    assertThat(validUnit("Items")).isTrue();
  }

  @Test
  void unit_outsideSet_isRejected() {
    assertThat(validUnit("ounces")).isFalse();
    assertThat(validUnit("each")).isFalse();
    assertThat(validUnit("")).isFalse();
  }
}
