package com.example.mealprep.auth.testdata;

import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * Test data builder for the auth module. Defaults match the validator constraints so callers can
 * tweak only the field under test.
 */
public final class AuthTestData {

  private AuthTestData() {}

  /** Default raw password — long enough to satisfy {@code @ValidPassword}. */
  public static final String DEFAULT_PASSWORD = "correct-horse-battery";

  public static UserBuilder user() {
    return new UserBuilder();
  }

  public static SessionBuilder session() {
    return new SessionBuilder();
  }

  public static RegisterRequest registerRequest() {
    return new RegisterRequest("user-" + shortId(), DEFAULT_PASSWORD);
  }

  public static RegisterRequest registerRequest(String username) {
    return new RegisterRequest(username, DEFAULT_PASSWORD);
  }

  public static RegisterRequest registerRequest(String username, String password) {
    return new RegisterRequest(username, password);
  }

  public static LoginRequest loginRequest(String username, String password) {
    return new LoginRequest(username, password);
  }

  /** Used for tests asserting the cookie-not-equal-to-hash invariant. */
  public static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  public static final class UserBuilder {
    private UUID id = UUID.randomUUID();
    private String username = "user-" + shortId();
    private String passwordHash = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEF0123456789ABCDEFGHIJ";
    private Instant passwordUpdatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private Instant deletedAt;

    public UserBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    public UserBuilder withUsername(String username) {
      this.username = username;
      return this;
    }

    public UserBuilder withPasswordHash(String passwordHash) {
      this.passwordHash = passwordHash;
      return this;
    }

    public UserBuilder softDeleted() {
      this.deletedAt = Instant.now();
      return this;
    }

    public User build() {
      return User.builder()
          .id(id)
          .username(username)
          .usernameNormalised(username.toLowerCase(Locale.ROOT))
          .passwordHash(passwordHash)
          .passwordUpdatedAt(passwordUpdatedAt)
          .failedLoginCount(0)
          .deletedAt(deletedAt)
          .build();
    }
  }

  public static final class SessionBuilder {
    private UUID id = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private String tokenHash = UUID.randomUUID().toString().replace("-", "");
    private Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private Instant expiresAt = issuedAt.plus(30, ChronoUnit.DAYS);
    private Instant revokedAt;

    public SessionBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    public SessionBuilder withUserId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public SessionBuilder withTokenHash(String tokenHash) {
      this.tokenHash = tokenHash;
      return this;
    }

    public SessionBuilder withExpiresAt(Instant expiresAt) {
      this.expiresAt = expiresAt;
      return this;
    }

    public SessionBuilder revoked() {
      this.revokedAt = Instant.now();
      return this;
    }

    public Session build() {
      return Session.builder()
          .id(id)
          .userId(userId)
          .tokenHash(tokenHash)
          .issuedAt(issuedAt)
          .expiresAt(expiresAt)
          .lastSeenAt(issuedAt)
          .revokedAt(revokedAt)
          .build();
    }
  }
}
