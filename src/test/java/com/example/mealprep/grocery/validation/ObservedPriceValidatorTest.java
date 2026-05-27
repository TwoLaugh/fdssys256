package com.example.mealprep.grocery.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ObservedPriceValidator} (grocery-01d). The rule (lld/grocery.md line 777): a
 * non-negative integer pence value, &le; 1,000,000; null accepted (optional field). Pure unit test
 * — the {@code ConstraintValidatorContext} is unused by the body, so a {@code null} context is
 * fine.
 */
class ObservedPriceValidatorTest {

  private final ObservedPriceValidator validator = new ObservedPriceValidator();

  private boolean valid(Integer value) {
    return validator.isValid(value, null);
  }

  @Test
  void null_isAccepted_optionalField() {
    assertThat(valid(null)).isTrue();
  }

  @Test
  void zero_isAccepted() {
    assertThat(valid(0)).isTrue();
  }

  @Test
  void typicalPrice_isAccepted() {
    assertThat(valid(450)).isTrue(); // £4.50
  }

  @Test
  void upperBound_isAccepted_inclusive() {
    assertThat(valid(1_000_000)).isTrue();
  }

  @Test
  void negative_isRejected() {
    assertThat(valid(-1)).isFalse();
  }

  @Test
  void aboveUpperBound_isRejected_poundPenceMixUp() {
    // £4.50 mistakenly entered as 450,000 pence-as-pounds → 45,000,001 etc. caught.
    assertThat(valid(1_000_001)).isFalse();
    assertThat(valid(45_000_000)).isFalse();
  }
}
