package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.config.AuthProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Basic password-strength rules used by both the {@code @ValidPassword} validator (for raw input
 * shape) and {@code AuthServiceImpl.register} (post-validation safety check that the password is
 * not equal to the username).
 *
 * <p>01a covers length + whitespace + username-equality. Breached-password block list is added in
 * 01b — this class does NOT load any list at startup.
 */
@Component
public class PasswordStrengthValidator {

  private final int minLength;
  private final int maxLength;

  public PasswordStrengthValidator(AuthProperties authProperties) {
    this.minLength = authProperties.passwordMinLength();
    this.maxLength = authProperties.passwordMaxLength();
  }

  /** Reasons a password may be rejected. Used as machine-readable codes in error responses. */
  public enum Reason {
    TOO_SHORT,
    TOO_LONG,
    LEADING_OR_TRAILING_WHITESPACE,
    MATCHES_USERNAME
  }

  /**
   * Apply every basic rule. Returns the full list of violated reasons; empty list means valid.
   * {@code username} may be null for shape-only validation (the annotation validator path); the
   * service-layer call always passes a username.
   */
  public List<Reason> evaluate(String password, String username) {
    List<Reason> reasons = new ArrayList<>(2);
    if (password == null || password.length() < minLength) {
      reasons.add(Reason.TOO_SHORT);
      return reasons;
    }
    if (password.length() > maxLength) {
      reasons.add(Reason.TOO_LONG);
    }
    if (!password.equals(password.strip())) {
      reasons.add(Reason.LEADING_OR_TRAILING_WHITESPACE);
    }
    if (username != null
        && !username.isEmpty()
        && password.toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
      reasons.add(Reason.MATCHES_USERNAME);
    }
    return reasons;
  }

  public int minLength() {
    return minLength;
  }

  public int maxLength() {
    return maxLength;
  }
}
