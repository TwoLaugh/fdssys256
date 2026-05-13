package com.example.mealprep.planner.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
