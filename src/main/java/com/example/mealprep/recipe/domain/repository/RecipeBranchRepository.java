package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RecipeBranch}.
 *
 * <p>{@code findAllByRecipeId} ships in recipe-01b alongside the public branches[] DTO and {@code
 * GET /api/v1/recipes/{recipeId}/branches}. In 01a/01b every recipe has exactly one auto-created
 * 'main' branch; non-main rows arrive with recipe-01d.
 */
public interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {

  /** Returns all branches for a given recipe id; ordering is applied at the mapper layer. */
  List<RecipeBranch> findAllByRecipeId(UUID recipeId);
}
