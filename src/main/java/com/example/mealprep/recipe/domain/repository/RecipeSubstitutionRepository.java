package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RecipeSubstitution}. Verbatim from LLD lines 495-498 (with the
 * {@link SubstitutionState} rename applied — see ticket 01e for the divergence note).
 */
public interface RecipeSubstitutionRepository extends JpaRepository<RecipeSubstitution, UUID> {

  /**
   * All substitutions for a recipe in the given state, sorted {@code last_applied_at DESC NULLS
   * LAST}. The {@code getActiveSubstitutions} flow calls with {@code state = ACCEPTED}.
   */
  List<RecipeSubstitution> findAllByRecipeIdAndStateOrderByLastAppliedAtDesc(
      UUID recipeId, SubstitutionState state);

  /**
   * All substitutions on a single version in the given state, sorted {@code last_applied_at DESC
   * NULLS LAST}. The {@code getSubstitutionsForVersion} and {@code SubstitutionOverlayApplier}
   * flows call with {@code state = ACCEPTED}; the recipe-substitution-state-filter ticket's {@code
   * state} query param routes the other explicit states here too.
   */
  List<RecipeSubstitution> findAllByVersionIdAndStateOrderByLastAppliedAtDesc(
      UUID versionId, SubstitutionState state);

  /**
   * All substitutions on a single version regardless of state, sorted {@code last_applied_at DESC
   * NULLS LAST}. Backs {@code state=ALL} on the substitutions list
   * (recipe-substitution-state-filter ticket).
   */
  List<RecipeSubstitution> findAllByVersionIdOrderByLastAppliedAtDesc(UUID versionId);
}
