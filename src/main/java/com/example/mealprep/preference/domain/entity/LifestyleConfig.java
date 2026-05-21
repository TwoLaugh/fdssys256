package com.example.mealprep.preference.domain.entity;

import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
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
 * Aggregate root for a user's Tier-3 lifestyle config. One row per user (unique on {@code
 * user_id}). The JSONB {@code document} column is mapped via {@link JsonBinaryType} from
 * hypersistence-utils-hibernate-63; {@link LifestyleConfigDocument} is the typed view consumed by
 * the planner and other downstream modules.
 *
 * <p>{@code @Version} guards optimistic concurrency on the whole document; mismatch on PUT surfaces
 * as {@code OptimisticLockingFailureException} → 409.
 *
 * <p>{@code lastReviewPromptAt} starts NULL on initialisation and is set by the deferred
 * behavioural-drift scanner (C-B-046) when a "is this still accurate?" nudge is emitted; the {@code
 * markReviewed} endpoint resets it to NULL.
 *
 * <p>The {@code @Column(name="version")} on {@link HardConstraints} is called {@code version} but
 * the lifestyle-config schema uses {@code optimistic_version} per LLD — the field stays {@code
 * optimisticVersion} on the entity to mirror that.
 */
@Entity
@Table(name = "preference_lifestyle_config")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LifestyleConfig {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true, updatable = false)
  private UUID userId;

  @Type(JsonBinaryType.class)
  @Column(name = "document", nullable = false, columnDefinition = "jsonb")
  private LifestyleConfigDocument document;

  @Column(name = "last_review_prompt_at")
  private Instant lastReviewPromptAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
