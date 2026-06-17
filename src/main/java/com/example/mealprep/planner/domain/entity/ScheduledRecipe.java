package com.example.mealprep.planner.domain.entity;

import com.example.mealprep.planner.api.dto.Addition;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Recipe chosen for a {@link MealSlot}. Cross-module IDs ({@code recipeId}, {@code
 * recipeVersionId}, {@code recipeBranchId}) are deliberately soft refs — no DB-level FK to {@code
 * recipe_recipes} / {@code recipe_versions} / {@code recipe_branches} per LLD §Database lines
 * 261-263.
 */
@Entity
@Table(name = "planner_scheduled_recipes")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ScheduledRecipe {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "slot_id", nullable = false, unique = true)
  private MealSlot slot;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "recipe_version_id", nullable = false)
  private UUID recipeVersionId;

  @Column(name = "recipe_branch_id", nullable = false)
  private UUID recipeBranchId;

  @Column(name = "servings", nullable = false)
  private int servings;

  @Column(name = "batch_cook_session_id")
  private UUID batchCookSessionId;

  @Column(name = "augmentation_notes", length = 512)
  private String augmentationNotes;

  @Enumerated(EnumType.STRING)
  @Column(name = "augmentation_source", length = 16)
  private AugmentationSource augmentationSource;

  @Column(name = "phase2_addition", nullable = false)
  private boolean phase2Addition;

  /**
   * In-meal additions bolted onto this slot's main recipe in Phase 2 (portion-scaling + additions
   * design) — a JSONB list, soft refs to recipe/USDA like the id columns above. Defaults to an empty
   * list so pre-additions plans and the {@code NOT NULL DEFAULT '[]'} column agree.
   */
  @Type(JsonBinaryType.class)
  @Column(name = "additions", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<Addition> additions = new ArrayList<>();

  /**
   * Servings of this recipe the primary eater consumes — sized to the slot's per-meal calorie target
   * (Phase 1b, distinct from head-count {@code servings}). Drives grocery quantities (× factor) and
   * the "× N servings" UI; defaults to 1.0. Computed by {@code PortionScaler} at persist time from
   * the same per-meal targets the rollup's coverage uses, so the two never disagree.
   */
  @Column(name = "portion_factor", nullable = false)
  @Builder.Default
  private BigDecimal portionFactor = BigDecimal.ONE;
}
