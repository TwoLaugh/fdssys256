package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Aggregate root for a recipe. Per LLD V20260601120000, the root deliberately holds no
 * {@code @OneToMany} to versions or branches — those are queried via dedicated repositories so the
 * root SELECT stays lean.
 *
 * <p>{@code currentBranchId} is populated atomically with the auto-created 'main' branch in the
 * single create transaction (see {@code RecipeServiceImpl.createRecipe}). The pattern: save root
 * with {@code currentBranchId=null}, save branch (gets id), {@code recipe.setCurrentBranchId(id)} —
 * JPA dirty-check picks up the change; one extra UPDATE on commit.
 */
@Entity
@Table(name = "recipe_recipes")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Recipe {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "catalogue", nullable = false, length = 16)
  private Catalogue catalogue;

  @Column(name = "name", nullable = false, length = 160)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(name = "current_branch_id")
  private UUID currentBranchId;

  @Enumerated(EnumType.STRING)
  @Column(name = "data_quality", nullable = false, length = 16)
  private DataQuality dataQuality;

  @Enumerated(EnumType.STRING)
  @Column(name = "nutrition_status", nullable = false, length = 16)
  private NutritionStatus nutritionStatus;

  @Column(name = "forked_from_recipe_id")
  private UUID forkedFromRecipeId;

  @Column(name = "last_used_in_plan_at")
  private Instant lastUsedInPlanAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  /**
   * Relative storage key for the recipe's hero image (e.g. {@code recipes/ab/<uuid>-<hash>.jpg}).
   * Resolved against {@code mealprep.recipe.image-storage.base-dir} at serve time. Null when no
   * image has been uploaded. Added in recipe-02a.
   */
  @Column(name = "image_url", length = 512)
  private String imageUrl;

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
