package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.mealprep.auth.validation.ValidUsernameValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ValidUsernameValidator}. The shape pattern is {@code ^[a-zA-Z0-9_-]{3,32}$}
 * (matching the OpenAPI contract — no dot, 32-char ceiling). On top of shape, the validator rejects
 * values that start/end with a separator and reserved names (default {@code admin}/{@code
 * root}/{@code system}/{@code support}). Tests pin: null rejected; the 3 and 32 length boundaries
 * (and just-outside 2 / 33); allowed vs disallowed characters; anchors reject internal newlines /
 * leading-trailing junk; separator-edge rejection; reserved-name rejection (case insensitive). The
 * context is never used by this validator so a bare mock suffices.
 */
class ValidUsernameValidatorTest {

  // No-arg constructor falls back to the built-in default reserved-name list.
  private final ValidUsernameValidator validator = new ValidUsernameValidator();
  private final ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

  @Test
  void nullUsername_isInvalid() {
    assertThat(validator.isValid(null, context)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abc",
        "a_b",
        "a-b",
        "ABC",
        "user123",
        "a_b_c",
        "a1234567890123456789012345678901"
      })
  void acceptsValidUsernames(String value) {
    assertThat(validator.isValid(value, context)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"_ab", "-ab", "ab_", "ab-", "___", "---", "_a_", "-a-"})
  void rejectsLeadingOrTrailingSeparator(String value) {
    assertThat(validator.isValid(value, context)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "ADMIN", "Root", "system", "SUPPORT", "support"})
  void rejectsReservedNames_caseInsensitive(String value) {
    assertThat(validator.isValid(value, context)).isFalse();
  }

  @Test
  void respectsConfiguredReservedNameList() {
    // A configured list replaces the defaults: 'admin' is now allowed, 'banned' is rejected.
    com.example.mealprep.auth.config.AuthProperties props =
        new com.example.mealprep.auth.config.AuthProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new com.example.mealprep.auth.config.AuthProperties.Username(
                java.util.Set.of("banned")));
    ValidUsernameValidator configured = new ValidUsernameValidator(props);
    assertThat(configured.isValid("admin", context)).isTrue();
    assertThat(configured.isValid("banned", context)).isFalse();
    assertThat(configured.isValid("BANNED", context)).isFalse();
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
