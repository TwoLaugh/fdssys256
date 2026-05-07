package com.example.mealprep.auth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit row for every login attempt — both successful and failed, both known-user and
 * unknown-user.
 *
 * <p>Recording attempts for unknown usernames keeps the throttle service from becoming a
 * username-enumeration oracle: same rules apply whether or not the username matches a real user.
 *
 * <p>{@code passwordAttempt} is deliberately not stored. The row carries enough context for
 * forensic review without ever touching the credential.
 */
@Entity
@Table(name = "auth_login_attempts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoginAttempt {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "username_normalised", nullable = false, length = 64, updatable = false)
  private String usernameNormalised;

  @Column(name = "user_id", updatable = false)
  private UUID userId;

  @Column(name = "source_ip", nullable = false, length = 45, updatable = false)
  private String sourceIp;

  @Column(name = "succeeded", nullable = false, updatable = false)
  private boolean succeeded;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_reason", length = 32, updatable = false)
  private LoginFailureReason failureReason;

  @Column(name = "attempted_at", nullable = false, updatable = false)
  private Instant attemptedAt;
}
