package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RecipeBranch}.
 *
 * <p>{@code findAllByRecipeId} ships in recipe-01b alongside the public branches[] DTO and {@code
 * GET /api/v1/recipes/{recipeId}/branches}. recipe-01d appends {@link #findByRecipeIdAndName}
 * (pre-check before INSERT so callers see a clean 409 instead of {@code
 * DataIntegrityViolationException}) plus the explicit-sort variant used by the get-single endpoint.
 */
public interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {

  /** Returns all branches for a given recipe id; ordering is applied at the mapper layer. */
  List<RecipeBranch> findAllByRecipeId(UUID recipeId);

  /**
   * Batched variant for the library list read ({@code GET /api/v1/recipes}) — one query for the
   * whole page instead of one per row. The service groups by recipe id; ordering is applied at the
   * mapper layer as for {@link #findAllByRecipeId}.
   */
  List<RecipeBranch> findAllByRecipeIdIn(java.util.Collection<UUID> recipeIds);

  /**
   * Returns the branch with the given name within the recipe, or empty if missing. Used by
   * branch-creation to surface 409 {@code recipe-branch-name-conflict} before the INSERT trips the
   * DB unique constraint.
   */
  Optional<RecipeBranch> findByRecipeIdAndName(UUID recipeId, String name);
}
