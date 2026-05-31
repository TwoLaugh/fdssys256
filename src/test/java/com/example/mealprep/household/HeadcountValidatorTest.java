package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.validation.HeadcountValidator;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link HeadcountValidator} (household-2). {@code null} is optional-valid; the
 * accepted range is {@code [1, 16]}.
 */
class HeadcountValidatorTest {

  private final HeadcountValidator validator = new HeadcountValidator();

  @Test
  void nullHeadcount_isValid() {
    assertThat(validator.isValid(null, null)).isTrue();
  }

  @Test
  void boundaryValues_areValid() {
    assertThat(validator.isValid(1, null)).isTrue();
    assertThat(validator.isValid(16, null)).isTrue();
  }

  @Test
  void midRange_isValid() {
    assertThat(validator.isValid(8, null)).isTrue();
  }

  @Test
  void belowMinimum_isInvalid() {
    assertThat(validator.isValid(0, null)).isFalse();
    assertThat(validator.isValid(-3, null)).isFalse();
  }

  @Test
  void aboveMaximum_isInvalid() {
    assertThat(validator.isValid(17, null)).isFalse();
    assertThat(validator.isValid(10_000, null)).isFalse();
  }
}
