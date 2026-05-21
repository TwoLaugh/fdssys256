package com.example.mealprep.core.origin.testdata;

import com.example.mealprep.auth.domain.entity.ServiceToken;
import com.example.mealprep.core.origin.Origin;
import java.time.Instant;
import java.util.UUID;

/**
 * Fixture builders for {@link ServiceToken}. Used by the auth-provider test and any future test
 * that needs a populated row. Lives in {@code testdata} per the project's convention.
 */
public final class ServiceTokenTestData {

  private ServiceTokenTestData() {}

  /** A live, enabled, never-revoked token permitting one origin. */
  public static ServiceToken aLiveToken(String hash, Origin permittedOrigin) {
    Instant now = Instant.parse("2026-05-21T10:00:00Z");
    return ServiceToken.builder()
        .id(UUID.randomUUID())
        .tokenHash(hash)
        .name("test-token-" + permittedOrigin.name().toLowerCase())
        .permittedOrigins(new String[] {permittedOrigin.name()})
        .enabled(true)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** A token that has been revoked — should be rejected on lookup. */
  public static ServiceToken aRevokedToken(String hash, Origin permittedOrigin) {
    ServiceToken token = aLiveToken(hash, permittedOrigin);
    token.setRevokedAt(Instant.parse("2026-05-21T09:00:00Z"));
    return token;
  }

  /** A token that has been disabled — should be rejected on lookup. */
  public static ServiceToken aDisabledToken(String hash, Origin permittedOrigin) {
    ServiceToken token = aLiveToken(hash, permittedOrigin);
    token.setEnabled(false);
    return token;
  }
}
