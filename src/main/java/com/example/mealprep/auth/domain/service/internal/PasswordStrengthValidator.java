package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.config.AuthProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Basic password-strength rules used by both the {@code @ValidPassword} validator (for raw input
 * shape) and {@code AuthServiceImpl.register} (post-validation safety check that the password is
 * not equal to the username, and not on the breached-password block list).
 *
 * <p>Length, whitespace, and username-equality come from 01a. The breached-password block list is
 * 01b — loaded once at construction from {@code auth/breached-passwords.txt} on the classpath. The
 * list is consulted for register and password-change paths only; login does NOT consult it, because
 * the attacker's existing password choice is already in the database and re-checking it gives no
 * defence value.
 */
@Component
public class PasswordStrengthValidator {

  private static final Logger log = LoggerFactory.getLogger(PasswordStrengthValidator.class);

  private static final String BREACH_LIST_CLASSPATH = "auth/breached-passwords.txt";

  private final int minLength;
  private final int maxLength;
  private final Set<String> breachedPasswords;

  @Autowired
  public PasswordStrengthValidator(AuthProperties authProperties) {
    this(authProperties, new ClassPathResource(BREACH_LIST_CLASSPATH));
  }

  /** Test seam — pass an arbitrary {@link Resource} so unit tests can supply an in-memory list. */
  PasswordStrengthValidator(AuthProperties authProperties, Resource breachListResource) {
    this.minLength = authProperties.passwordMinLength();
    this.maxLength = authProperties.passwordMaxLength();
    this.breachedPasswords = loadBreachList(breachListResource);
  }

  /** Reasons a password may be rejected. Used as machine-readable codes in error responses. */
  public enum Reason {
    TOO_SHORT,
    TOO_LONG,
    LEADING_OR_TRAILING_WHITESPACE,
    MATCHES_USERNAME,
    BREACHED
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
    if (breachedPasswords.contains(password.toLowerCase(Locale.ROOT))) {
      reasons.add(Reason.BREACHED);
    }
    return reasons;
  }

  public int minLength() {
    return minLength;
  }

  public int maxLength() {
    return maxLength;
  }

  /** Test/diagnostics — exposes the loaded breach-list size. */
  public int breachListSize() {
    return breachedPasswords.size();
  }

  private static Set<String> loadBreachList(Resource resource) {
    Set<String> set = new HashSet<>();
    if (resource == null || !resource.exists()) {
      log.warn(
          "Breached-password block list not found on classpath; password breach check disabled");
      return Set.of();
    }
    try (InputStream in = resource.getInputStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        set.add(trimmed.toLowerCase(Locale.ROOT));
      }
    } catch (IOException e) {
      // Fail closed-but-loud: log and continue with an empty list rather than crash startup. The
      // other checks still run; ops will see the warning.
      log.warn("Failed to load breached-password block list: {}", e.getMessage());
      return Set.of();
    }
    log.info("Loaded breached-password block list size={}", set.size());
    return Set.copyOf(set);
  }
}
