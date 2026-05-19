package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.mealprep.auth.validation.ValidUsernameValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ValidUsernameValidator}. The pattern is {@code ^[a-zA-Z0-9_-]{3,32}$}.
 * Tests pin: null rejected; the 3 and 32 length boundaries (and just-outside 2 / 33); allowed vs
 * disallowed characters; and that the anchors reject internal newlines / leading-trailing junk.
 * This kills the null-branch and the regex literal/quantifier mutants. The context is never used by
 * this validator so a bare mock suffices.
 */
class ValidUsernameValidatorTest {

  private final ValidUsernameValidator validator = new ValidUsernameValidator();
  private final ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

  @Test
  void nullUsername_isInvalid() {
    assertThat(validator.isValid(null, context)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"abc", "a_b", "a-b", "ABC", "user123", "___", "a1234567890123456789012345678901"})
  void acceptsValidUsernames(String value) {
    assertThat(validator.isValid(value, context)).isTrue();
  }

  @Test
  void acceptsExactlyThirtyTwoChars() {
    assertThat(validator.isValid("a".repeat(32), context)).isTrue();
  }

  @Test
  void rejectsThirtyThreeChars() {
    assertThat(validator.isValid("a".repeat(33), context)).isFalse();
  }

  @Test
  void rejectsTwoChars() {
    assertThat(validator.isValid("ab", context)).isFalse();
  }

  @Test
  void acceptsExactlyThreeChars() {
    assertThat(validator.isValid("abc", context)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "ab", "user name", "user@name", "user.name", "user!", "café", "a/b", "ab\nc"})
  void rejectsInvalidUsernames(String value) {
    assertThat(validator.isValid(value, context)).isFalse();
  }

  @Test
  void rejectsValidCoreWithLeadingWhitespace_anchorEnforced() {
    assertThat(validator.isValid(" abc", context)).isFalse();
  }

  @Test
  void rejectsValidCoreWithTrailingNewline_anchorEnforced() {
    assertThat(validator.isValid("abc\n", context)).isFalse();
  }
}
