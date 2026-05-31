package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.validation.SlotKeyValidator;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link SlotKeyValidator} (household-2): kebab-case format, length bound, and
 * the built-in slot-kind collision check.
 */
class SlotKeyValidatorTest {

  private final SlotKeyValidator validator = new SlotKeyValidator();

  @Test
  void nullKey_isLeftToNotNull_returnsValid() {
    assertThat(validator.isValid(null, null)).isTrue();
  }

  @Test
  void wellFormedKebabCaseKey_isValid() {
    assertThat(validator.isValid("late-snack", null)).isTrue();
    assertThat(validator.isValid("supper", null)).isTrue();
    assertThat(validator.isValid("meal-2", null)).isTrue();
  }

  @Test
  void emptyKey_isInvalid() {
    assertThat(validator.isValid("", null)).isFalse();
  }

  @Test
  void keyOver48Chars_isInvalid() {
    assertThat(validator.isValid("a".repeat(49), null)).isFalse();
    assertThat(validator.isValid("a".repeat(48), null)).isTrue();
  }

  @Test
  void nonKebabCaseKey_isInvalid() {
    assertThat(validator.isValid("Late Snack", null)).isFalse(); // spaces + uppercase
    assertThat(validator.isValid("late_snack", null)).isFalse(); // underscore
    assertThat(validator.isValid("Supper", null)).isFalse(); // uppercase
    assertThat(validator.isValid("emoji🍔", null)).isFalse();
  }

  @Test
  void keyCollidingWithBuiltInSlotKind_isInvalid() {
    assertThat(validator.isValid("breakfast", null)).isFalse();
    assertThat(validator.isValid("lunch", null)).isFalse();
    assertThat(validator.isValid("dinner", null)).isFalse();
    assertThat(validator.isValid("snack", null)).isFalse();
    assertThat(validator.isValid("custom", null)).isFalse();
  }
}
