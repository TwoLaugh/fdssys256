package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RecipeBranch}.
 *
 * <p>The "list branches for recipe" query (and the {@code findAllByRecipeId} method that supports
 * it) lands with recipe-01b alongside the user-facing branches[] DTO. 01a only ever inserts the
 * auto-created 'main' branch.
 */
public interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {}
