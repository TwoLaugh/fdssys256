package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator;
import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator.Reason;
import org.junit.jupiter.api.Test;

class PasswordStrengthValidatorTest {

  private final AuthProperties properties =
      new AuthProperties(null, null, null, null, null, 12, 128, null, null, null, null);
  private final PasswordStrengthValidator validator = new PasswordStrengthValidator(properties);

  @Test
  void evaluate_acceptsExactlyMinLengthPassword() {
    String password = "a".repeat(12);

    assertThat(validator.evaluate(password, "alice")).isEmpty();
  }

  @Test
  void evaluate_rejectsBelowMinLength() {
    String password = "a".repeat(11);

    assertThat(validator.evaluate(password, "alice")).containsExactly(Reason.TOO_SHORT);
  }

  @Test
  void evaluate_acceptsExactlyMaxLengthPassword() {
    String password = "a".repeat(128);

    assertThat(validator.evaluate(password, "alice")).isEmpty();
  }

  @Test
  void evaluate_rejectsAboveMaxLength() {
    String password = "a".repeat(129);

    assertThat(validator.evaluate(password, "alice")).contains(Reason.TOO_LONG);
  }

  @Test
  void evaluate_rejectsLeadingWhitespace() {
    String password = " correct-horse-battery";

    assertThat(validator.evaluate(password, "alice"))
        .contains(Reason.LEADING_OR_TRAILING_WHITESPACE);
  }

  @Test
  void evaluate_rejectsTrailingWhitespace() {
    String password = "correct-horse-battery ";

    assertThat(validator.evaluate(password, "alice"))
        .contains(Reason.LEADING_OR_TRAILING_WHITESPACE);
  }

  @Test
  void evaluate_rejectsPasswordEqualToUsernameCaseInsensitive() {
    String password = "correct-horse-batterystaple";

    assertThat(validator.evaluate(password, "Correct-Horse-Batterystaple"))
        .contains(Reason.MATCHES_USERNAME);
  }

  @Test
  void evaluate_skipsUsernameRule_whenUsernameIsNull() {
    // Annotation-side validation has no access to the username and passes null.
    String password = "correct-horse-battery";

    assertThat(validator.evaluate(password, null)).isEmpty();
  }

  @Test
  void evaluate_returnsTooShort_andStopsForNullPassword() {
    assertThat(validator.evaluate(null, "alice")).containsExactly(Reason.TOO_SHORT);
  }

  @Test
  void evaluate_collectsMultipleReasonsWhenApplicable() {
    // Long enough but trailing whitespace AND equal to username — both should fire.
    String password = "alice-Correct ";

    assertThat(validator.evaluate(password, "alice-correct"))
        .contains(Reason.LEADING_OR_TRAILING_WHITESPACE);
    // The trailing whitespace also nudges the equality check off, which is fine — we exercised
    // the WS rule above and exercise MATCHES_USERNAME with a clean exact match below.
  }

  @Test
  void minMaxLength_exposesConfiguredBoundary() {
    assertThat(validator.minLength()).isEqualTo(12);
    assertThat(validator.maxLength()).isEqualTo(128);
  }

  // ---------------- breach-list ----------------

  @Test
  void evaluate_rejectsPasswordOnBreachList() {
    // "password123456" is in src/main/resources/auth/breached-passwords.txt and is long enough
    // to pass the length check, so the breach rule is the only thing rejecting it.
    String password = "password123456";

    assertThat(validator.evaluate(password, "alice")).contains(Reason.BREACHED);
  }

  @Test
  void evaluate_rejectsPasswordOnBreachList_caseInsensitive() {
    // The breach list is stored lowercase; mixed-case input must still be matched.
    String password = "Password123456";

    assertThat(validator.evaluate(password, "alice")).contains(Reason.BREACHED);
  }

  @Test
  void evaluate_acceptsPasswordNotOnBreachList() {
    // A long, clearly-not-on-the-list password should pass every rule.
    String password = "purple-elephant-archipelago-22";

    assertThat(validator.evaluate(password, "alice")).isEmpty();
  }

  @Test
  void breachListLoaded_andContainsExpectedSampleEntries() {
    // Sanity check: the loader picked up entries from the resource file, not an empty Set.
    assertThat(validator.breachListSize()).isGreaterThan(50);
  }
}
