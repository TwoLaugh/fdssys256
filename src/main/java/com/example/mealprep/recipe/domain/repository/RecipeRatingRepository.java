package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto;
import com.example.mealprep.recipe.domain.entity.RecipeRating;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RecipeRating} (recipe-02b). Package-private isolation is
 * enforced by {@code RecipeBoundaryTest} (ArchUnit) — only the in-module {@code
 * domain.service.internal} package injects it.
 */
public interface RecipeRatingRepository extends JpaRepository<RecipeRating, UUID> {

  /**
   * A user has at most one rating per version (unique index {@code
   * uq_recipe_ratings_version_user}).
   */
  Optional<RecipeRating> findByVersionIdAndUserId(UUID versionId, UUID userId);

  Page<RecipeRating> findByVersionIdOrderByCreatedAtDesc(UUID versionId, Pageable p);

  Page<RecipeRating> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable p);

  Page<RecipeRating> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable p);

  /** Loads a rating scoped to its owner; used by update/delete so non-owners get a 404. */
  Optional<RecipeRating> findByIdAndUserId(UUID id, UUID userId);

  /** Aggregate across all ratings for a single version. */
  @Query(
      """
      select new com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto(
          r.versionId,
          avg(r.taste), avg(r.effortWorthIt), avg(r.portionFit),
          avg(r.repeatValue), avg(r.aggregate), count(r))
      from RecipeRating r
      where r.versionId = :versionId
      group by r.versionId
      """)
  RecipeRatingSummaryDto aggregateByVersion(@Param("versionId") UUID versionId);

  /** Aggregate across all ratings on all versions of a recipe. */
  @Query(
      """
      select new com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto(
          null,
          avg(r.taste), avg(r.effortWorthIt), avg(r.portionFit),
          avg(r.repeatValue), avg(r.aggregate), count(r))
      from RecipeRating r
      where r.recipeId = :recipeId
      """)
  RecipeRatingSummaryDto aggregateByRecipe(@Param("recipeId") UUID recipeId);
}
