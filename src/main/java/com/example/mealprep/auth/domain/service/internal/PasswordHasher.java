package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.config.AuthProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring Security's {@link BCryptPasswordEncoder} so the rest of the auth
 * module talks to a single, mockable hasher instead of the encoder directly. Cost factor is sourced
 * from {@link AuthProperties#bcryptCost()} (default 12).
 */
@Component
public class PasswordHasher {

  private final int cost;
  private final BCryptPasswordEncoder encoder;

  public PasswordHasher(AuthProperties authProperties) {
    this.cost = authProperties.bcryptCost();
    this.encoder = new BCryptPasswordEncoder(cost);
  }

  /** Returns the configured BCrypt cost factor. Exposed for assertions in tests. */
  public int cost() {
    return cost;
  }

  /** Hashes a raw password. Never returns null; never logs the input. */
  public String hash(String rawPassword) {
    if (rawPassword == null) {
      throw new IllegalArgumentException("rawPassword must not be null");
    }
    return encoder.encode(rawPassword);
  }

  /**
   * Verifies a raw password against a previously-stored hash. Returns false (never throws) on
   * mismatch.
   */
  public boolean verify(String rawPassword, String hash) {
    if (rawPassword == null || hash == null) {
      return false;
    }
    return encoder.matches(rawPassword, hash);
  }
}
