package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A user's multi-dimensional rating of a single {@link RecipeVersion}, per ticket recipe-02b and
 * {@code design/recipe-system.md} §Multi-dimensional rating.
 *
 * <p>Ratings are <strong>per-version</strong> — they attach to the planned slot's pinned version,
 * not the recipe. {@code recipeId} is denormalised onto the row only for fast recipe-level
 * aggregate lookups; the enforced FK lives on {@code version_id} (cascade-delete on the migration).
 *
 * <p>{@code taste} is the required core signal; the other three dimensions are optional (one-tap
 * default UX supplies only {@code taste}). {@code aggregate} is computed server-side at write time
 * by {@code RecipeRatingServiceImpl} — the client never supplies it.
 */
@Entity
@Table(name = "recipe_ratings")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeRating {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false, updatable = false)
  private UUID recipeId;

  @Column(name = "version_id", nullable = false, updatable = false)
  private UUID versionId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "household_id", updatable = false)
  private UUID householdId;

  @Column(name = "slot_id", updatable = false)
  private UUID slotId;

  /**
   * 0-100, required. The {@code chk_taste_required} DB constraint mirrors this {@code @NotNull}.
   */
  @NotNull
  @Column(name = "taste", nullable = false)
  private Integer taste;

  @Column(name = "effort_worth_it")
  private Integer effortWorthIt;

  @Column(name = "portion_fit")
  private Integer portionFit;

  @Column(name = "repeat_value")
  private Integer repeatValue;

  /** Weighted blend, computed at write time. 0-100. */
  @Column(name = "aggregate", nullable = false)
  private int aggregate;

  @Column(name = "notes", length = 1000)
  private String notes;

  @Column(name = "trace_id")
  private UUID traceId;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
