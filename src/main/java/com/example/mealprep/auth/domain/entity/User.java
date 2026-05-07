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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Auth-module aggregate root.
 *
 * <p>{@code passwordHash} stores the BCrypt output (60 chars) — the raw password is never persisted
 * nor logged. {@code usernameNormalised} (lowercase, trimmed) is the lookup key and the basis for
 * the unique index; {@code username} preserves the user's chosen casing for display.
 *
 * <p>Soft-delete via {@code deletedAt}: every other module references {@code id} as a plain column
 * with no FK, so a hard delete would orphan references silently. Soft-delete keeps the row
 * reachable while {@code AuthServiceImpl} treats anything with non-null {@code deletedAt} as
 * not-authenticatable.
 */
@Entity
@Table(name = "auth_users")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "username", nullable = false, length = 64)
  private String username;

  @Column(name = "username_normalised", nullable = false, length = 64)
  private String usernameNormalised;

  @Column(name = "password_hash", nullable = false, length = 72)
  private String passwordHash;

  @Column(name = "password_updated_at", nullable = false)
  private Instant passwordUpdatedAt;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "last_login_ip", length = 45)
  private String lastLoginIp;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
