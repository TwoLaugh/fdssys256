package com.example.mealprep.grocery.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
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
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tier 3 aggregate root — per-user, per-provider session state. Per lld/grocery.md §Entities line
 * 363. {@code sessionState} (cookies + navigation cursor) is mapped to {@link ProviderSessionState}
 * via JSONB. {@code @Version} guards concurrent updates. NO card / payment data ever enters here.
 *
 * <p>TODO(grocery-crypto-followup): wrap {@code sessionState} in {@code EncryptedJsonConverter}
 * once {@code core.crypto} lands. 01a ships plaintext JSONB because v1 (FakeGroceryProvider) holds
 * no real cookies; encryption is a hard requirement before real Tesco automation (deferred ticket).
 */
@Entity
@Table(name = "grocery_provider_state")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GroceryProviderState {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "provider_key", nullable = false, length = 32)
  private String providerKey;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Type(JsonBinaryType.class)
  @Column(name = "session_state", columnDefinition = "jsonb")
  private ProviderSessionState sessionState;

  @Column(name = "session_expires_at")
  private Instant sessionExpiresAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "last_failure_at")
  private Instant lastFailureAt;

  @Column(name = "last_failure_reason", length = 255)
  private String lastFailureReason;

  @Column(name = "consecutive_failures", nullable = false)
  private int consecutiveFailures;

  @Column(name = "scheduled_refresh_enabled", nullable = false)
  private boolean scheduledRefreshEnabled;

  @Column(name = "refresh_top_n_ingredients", nullable = false)
  private int refreshTopNIngredients;

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
