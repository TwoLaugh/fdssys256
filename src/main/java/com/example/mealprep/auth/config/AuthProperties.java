package com.example.mealprep.auth.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the auth module — bound to the {@code mealprep.auth.*} prefix.
 *
 * <p>Defaults match the values in {@code lld/auth.md}; per-environment overrides live in {@code
 * application-<profile>.properties}. The throttle / lockout fields are defined here even though
 * their consumers ship in 01b — keeping a single change point for cookie + session config across
 * the module.
 *
 * <p>Validation lives in the compact constructor: every field falls back to its documented default
 * when the property is absent, and {@code passwordMinLength > passwordMaxLength} fails fast.
 */
@ConfigurationProperties(prefix = "mealprep.auth")
public record AuthProperties(
    Integer bcryptCost,
    Duration sessionTtl,
    String cookieName,
    Boolean cookieSecure,
    String cookieSameSite,
    Integer passwordMinLength,
    Integer passwordMaxLength,
    Throttle throttle,
    Lockout lockout,
    Session session,
    Username username) {

  public AuthProperties {
    if (bcryptCost == null) {
      bcryptCost = 12;
    }
    if (sessionTtl == null) {
      sessionTtl = Duration.ofDays(30);
    }
    if (cookieName == null || cookieName.isBlank()) {
      cookieName = "AUTH_SESSION";
    }
    if (cookieSecure == null) {
      cookieSecure = false;
    }
    if (cookieSameSite == null || cookieSameSite.isBlank()) {
      cookieSameSite = "Lax";
    }
    if (passwordMinLength == null) {
      passwordMinLength = 12;
    }
    if (passwordMaxLength == null) {
      passwordMaxLength = 128;
    }
    if (passwordMinLength > passwordMaxLength) {
      throw new IllegalArgumentException(
          "passwordMinLength ("
              + passwordMinLength
              + ") must be <= passwordMaxLength ("
              + passwordMaxLength
              + ")");
    }
    if (throttle == null) {
      throttle = new Throttle(null, null, null);
    }
    if (lockout == null) {
      lockout = new Lockout(null, null);
    }
    if (session == null) {
      session = new Session(null, null);
    }
    if (username == null) {
      username = new Username(null);
    }
  }

  /** Throttle thresholds — defined here even though enforcement is 01b. */
  public record Throttle(Duration window, Integer usernameMaxFailures, Integer ipMaxFailures) {
    public Throttle {
      if (window == null) {
        window = Duration.ofMinutes(15);
      }
      if (usernameMaxFailures == null) {
        usernameMaxFailures = 10;
      }
      if (ipMaxFailures == null) {
        ipMaxFailures = 30;
      }
    }
  }

  /** Lockout state-machine config — defined here even though enforcement is 01b. */
  public record Lockout(Integer threshold, Duration duration) {
    public Lockout {
      if (threshold == null) {
        threshold = 5;
      }
      if (duration == null) {
        duration = Duration.ofMinutes(15);
      }
    }
  }

  /**
   * Session-reaper config (Flow 6). {@code retainRevokedFor} is how long a revoked/expired session
   * row is kept before the nightly reaper hard-deletes it; {@code reaperCron} is the reaper's
   * schedule. Defaults: 7-day retention, {@code 0 15 3 * * *} (03:15 nightly).
   */
  public record Session(Duration retainRevokedFor, String reaperCron) {
    public Session {
      if (retainRevokedFor == null) {
        retainRevokedFor = Duration.ofDays(7);
      }
      if (reaperCron == null || reaperCron.isBlank()) {
        reaperCron = "0 15 3 * * *";
      }
    }
  }

  /**
   * Username-policy config. {@code reservedNames} is the case-insensitive block-list enforced by
   * {@code @ValidUsername} so well-known handles ({@code admin}, {@code root}, …) cannot be
   * registered. Stored normalised (trimmed, lower-cased) so the validator can compare directly.
   */
  public record Username(Set<String> reservedNames) {
    private static final Set<String> DEFAULT_RESERVED_NAMES =
        Set.of("admin", "root", "system", "support");

    public Username {
      if (reservedNames == null || reservedNames.isEmpty()) {
        reservedNames = DEFAULT_RESERVED_NAMES;
      } else {
        reservedNames =
            reservedNames.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
      }
    }
  }
}
