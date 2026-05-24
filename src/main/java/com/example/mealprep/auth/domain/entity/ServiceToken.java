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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Hashed opaque service token used by scheduled jobs and other non-user callers under origin
 * Pattern B. The plaintext token is shown once at mint time (admin CLI, out of scope here); only
 * its hash lives in this row. Per tickets/core/02b-origin-tracking-foundation.md §Service token
 * authentication + design/origin-tracking-pattern.md §Authentication Pattern B.
 *
 * <p>{@code permittedOrigins} is the whitelist of {@link com.example.mealprep.core.origin.Origin}
 * values this token can claim (string enum names — kept as a Postgres {@code text[]} via the same
 * pattern used in {@code adaptation.NutritionalKnowledgeEntry}, since enum {@code name()}s are
 * stable strings).
 *
 * <p>Lifecycle: {@code revokedAt} is the soft-delete; {@code enabled} is the temporary-disable
 * lever. Both are checked by {@link
 * com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider} on every authenticate.
 */
@Entity
@Table(name = "auth_service_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ServiceToken {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "token_hash", nullable = false, length = 96, unique = true)
  private String tokenHash;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "permitted_origins", nullable = false, columnDefinition = "text[]")
  private String[] permittedOrigins;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;
}
