package com.example.mealprep.grocery.domain.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Tier 1 aggregate root — a shopping list is DERIVED STATE: a snapshot rendered from a plan +
 * provisions at a moment in time, kept for history. Per lld/grocery.md §Entities line 359. Owns
 * {@code List<ShoppingListLine>} (cascade ALL, orphanRemoval). {@code @Version} guards the
 * aggregate (root + child lines) per lld/grocery.md §Concurrency line 977.
 *
 * <p>DIVERGENCE (ticket 01a, locked): {@code planGeneration} (not {@code planRevision}) maps 1:1
 * onto the shipped planner's {@code planner_plans.generation} counter. {@code supersededAt} is
 * non-null when a newer generation supersedes this list.
 */
@Entity
@Table(name = "shopping_lists")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ShoppingList {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "household_id")
  private UUID householdId;

  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Column(name = "plan_generation", nullable = false)
  private int planGeneration;

  @Column(name = "generated_at", nullable = false)
  private Instant generatedAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  @Column(name = "estimated_total_pence")
  private Integer estimatedTotalPence;

  @Column(name = "estimated_total_currency", nullable = false, length = 3)
  private String estimatedTotalCurrency;

  @Column(name = "cost_confidence", precision = 4, scale = 3)
  private BigDecimal costConfidence;

  @Column(name = "stale_ingredient_count", nullable = false)
  private int staleIngredientCount;

  @Column(name = "pantry_tracking_enabled", nullable = false)
  private boolean pantryTrackingEnabled;

  @Column(name = "notes", length = 255)
  private String notes;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "shoppingList",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<ShoppingListLine> lines = new ArrayList<>();
}
