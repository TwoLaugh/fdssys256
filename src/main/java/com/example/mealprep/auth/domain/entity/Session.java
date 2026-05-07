package com.example.mealprep.auth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Session row for an authenticated user. {@code tokenHash} is the SHA-256 hex of the raw cookie
 * value — the raw token only leaves the server in the {@code Set-Cookie} header on the issuing
 * response, and never touches the database, the response body, or logs.
 *
 * <p>Per the auth-01 ticket, {@code lastSeenAt} is set at issue time and is not updated on
 * subsequent requests; the column is preserved for a future ticket that may reintroduce sliding
 * expiry.
 *
 * <p>Cross-module access goes through {@link
 * com.example.mealprep.auth.domain.service.AuthQueryService}, never this entity directly.
 * {@code @ManyToOne} relationships are intentionally absent — {@code userId} is a plain column, in
 * line with the cross-module-references rule in the style guide.
 */
@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Session {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "issuing_ip", length = 45)
  private String issuingIp;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
