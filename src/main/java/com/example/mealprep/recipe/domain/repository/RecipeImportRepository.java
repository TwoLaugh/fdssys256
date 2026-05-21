package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeImport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RecipeImport}. Public so that the in-module {@code
 * domain.service.internal} package can inject it; cross-module isolation is enforced by {@code
 * RecipeBoundaryTest} (ArchUnit).
 */
public interface RecipeImportRepository extends JpaRepository<RecipeImport, UUID> {

  /**
   * Look up the (zero or one) provenance row for a recipe. Returns empty for manually-created
   * recipes from 01a (no import row was ever written).
   */
  Optional<RecipeImport> findByRecipeId(UUID recipeId);

  /**
   * Dedup probe used by {@code RecipeWriteApi.saveImportedRecipe} (discovery-01g). Returns the
   * existing provenance row whose {@code content_fingerprint} matches; empty if no prior import
   * shared the fingerprint. Backed by the partial UNIQUE index on {@code content_fingerprint}.
   */
  Optional<RecipeImport> findByContentFingerprint(String contentFingerprint);
}
