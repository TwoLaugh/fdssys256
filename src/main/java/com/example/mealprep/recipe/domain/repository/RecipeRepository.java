package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.Recipe;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Recipe}. {@code public} so the in-module {@code
 * domain.service.internal} package can inject it; cross-module isolation comes from {@code
 * RecipeBoundaryTest} (ArchUnit).
 */
public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

  /** Soft-delete-aware lookup. {@code GET /api/v1/recipes/{recipeId}} routes through this. */
  Optional<Recipe> findByIdAndDeletedAtIsNull(UUID id);
}
