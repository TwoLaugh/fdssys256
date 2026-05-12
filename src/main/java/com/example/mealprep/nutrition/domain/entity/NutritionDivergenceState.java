package com.example.mealprep.nutrition.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Dedup state for the divergence detector. One row per {@code (userId, onDate)} storing the
 * last-published set of diverged macro keys. Persisted so dedup survives a JVM restart and works
 * across multi-instance deployments. See {@code DivergenceDetector} and ticket nutrition-01h.
 */
@Entity
@Table(name = "nutrition_divergence_state")
@IdClass(NutritionDivergenceState.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class NutritionDivergenceState {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Id
  @Column(name = "on_date", nullable = false, updatable = false)
  private LocalDate onDate;

  /**
   * JSON array of macro keys (lowercase, e.g. {@code "protein"}) that were diverged at the last
   * publication. Empty list means no current divergence.
   */
  @Type(JsonBinaryType.class)
  @Column(name = "diverged_macros", nullable = false, columnDefinition = "jsonb")
  private List<String> divergedMacros;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Fresh empty row for a {@code (userId, onDate)} with no prior divergence publication. */
  public static NutritionDivergenceState empty(UUID userId, LocalDate onDate) {
    return NutritionDivergenceState.builder()
        .userId(userId)
        .onDate(onDate)
        .divergedMacros(new java.util.ArrayList<>())
        .updatedAt(Instant.EPOCH)
        .build();
  }

  /** Read-only view of the diverged macros as an immutable {@link Set} (preserves order). */
  public Set<String> divergedMacrosAsSet() {
    if (divergedMacros == null || divergedMacros.isEmpty()) {
      return Collections.emptySet();
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(divergedMacros));
  }

  /** Composite primary key shape required by {@link IdClass}. */
  public static class Key implements Serializable {
    private UUID userId;
    private LocalDate onDate;

    public Key() {}

    public Key(UUID userId, LocalDate onDate) {
      this.userId = userId;
      this.onDate = onDate;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key key)) {
        return false;
      }
      return Objects.equals(userId, key.userId) && Objects.equals(onDate, key.onDate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, onDate);
    }
  }
}
