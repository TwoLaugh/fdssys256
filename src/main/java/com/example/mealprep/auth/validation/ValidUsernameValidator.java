package com.example.mealprep.auth.validation;

import com.example.mealprep.auth.config.AuthProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implements {@link ValidUsername}. Three independent checks, all of which must pass:
 *
 * <ol>
 *   <li><b>Shape</b> — {@code ^[a-zA-Z0-9_-]{3,32}$}. Matches the shipped OpenAPI contract (3–32
 *       chars, ASCII alphanumerics plus underscore and hyphen; no dot).
 *   <li><b>Separator edges</b> — must not start or end with a separator ({@code _} or {@code -}),
 *       so handles like {@code -bob} / {@code bob_} are rejected.
 *   <li><b>Reserved names</b> — the (case-insensitive) reserved-name block-list from {@link
 *       AuthProperties.Username#reservedNames()} (default {@code admin}, {@code root}, {@code
 *       system}, {@code support}) cannot be claimed.
 * </ol>
 *
 * <p>The reserved-name set is resolved two ways (mirroring {@code PastOrNextDayValidator}):
 * Spring's {@code SpringConstraintValidatorFactory} constructor-injects {@link AuthProperties}; a
 * raw (non-Spring) {@code Validator} falls back to the no-arg constructor with the built-in
 * defaults.
 */
public class ValidUsernameValidator implements ConstraintValidator<ValidUsername, String> {

  private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");

  private final Set<String> reservedNames;

  /** Fallback used when instantiated outside Spring (raw {@code Validator} in a unit test). */
  public ValidUsernameValidator() {
    this(new AuthProperties.Username(null).reservedNames());
  }

  /** Used by Spring's {@code SpringConstraintValidatorFactory} to inject the configured list. */
  @Autowired
  public ValidUsernameValidator(AuthProperties authProperties) {
    this(authProperties.username().reservedNames());
  }

  private ValidUsernameValidator(Set<String> reservedNames) {
    this.reservedNames = reservedNames;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return false;
    }
    if (!PATTERN.matcher(value).matches()) {
      return false;
    }
    if (isSeparator(value.charAt(0)) || isSeparator(value.charAt(value.length() - 1))) {
      return false;
    }
    return !reservedNames.contains(value.toLowerCase(Locale.ROOT));
  }

  private static boolean isSeparator(char c) {
    return c == '_' || c == '-';
  }
}
