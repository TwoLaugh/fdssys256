package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RecipeIngredient}.
 *
 * <p>recipe-01e appends {@link #findMappingKeysByVersionId} (LLD line 504) used by the
 * substitution-create flow to validate that the original ingredient referenced by the request
 * actually exists in the version's ingredient list.
 */
public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, UUID> {

  /**
   * Returns the {@code ingredientMappingKey} of every {@link RecipeIngredient} on the given
   * version. Used by {@code createSubstitution} to gate-check the original ingredient.
   */
  @Query(
      """
      select i.ingredientMappingKey from RecipeIngredient i
       where i.version.id = :versionId
      """)
  List<String> findMappingKeysByVersionId(@Param("versionId") UUID versionId);
}
