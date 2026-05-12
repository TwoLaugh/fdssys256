package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-by-others contract for the recipe module.
 *
 * <p>01a landed {@link #getById}; recipe-01b appended {@link #getBranches} (powers {@code GET
 * /api/v1/recipes/{recipeId}/branches} and the {@code branches[]} field on {@link RecipeDto}) and
 * {@link #getImportProvenance} (powers {@code GET /api/v1/recipes/{recipeId}/import-provenance}).
 *
 * <p>recipe-01e appends the substitution read paths: {@link #getActiveSubstitutions}, {@link
 * #getSubstitutionsForVersion}, {@link #getSubstitution}, and {@link #getVersionWithSubstitutions}.
 */
public interface RecipeQueryService {

  /**
   * Returns the recipe + current-version body + branches[], or empty if the recipe is missing or
   * soft-deleted.
   */
  Optional<RecipeDto> getById(UUID recipeId);

  /**
   * Returns the branches for a recipe sorted by {@code createdAt ASC} (so 'main' is first). Throws
   * {@code RecipeNotFoundException} if the recipe doesn't exist or is soft-deleted.
   */
  List<RecipeBranchDto> getBranches(UUID recipeId);

  /**
   * Returns a single branch by id. Throws {@code RecipeBranchNotFoundException} if missing or if
   * the branch belongs to a different recipe than {@code recipeId} (we map cross-recipe lookups to
   * "not found" rather than 422 so we don't leak other users' branch ids).
   */
  Optional<RecipeBranchDto> getBranch(UUID recipeId, UUID branchId);

  /**
   * Internal cross-module helper — returns the persisted {@code CharacterFingerprintDto} for the
   * current version of the named branch, or empty if either is missing / the fingerprint hasn't
   * been populated yet (pre-01d main-branch v1 rows never wrote one). Not exposed via REST; future
   * tickets (01f, 01j) inject the service to call this.
   */
  Optional<CharacterFingerprintDto> getFingerprint(UUID recipeId, UUID branchId);

  /**
   * Returns the import provenance row for a recipe, or empty if the recipe was created manually (no
   * {@code recipe_imports} row was written). Callers are expected to map empty → 404 with {@code
   * recipe-import-not-found}, and "recipe missing" → 404 with {@code recipe-not-found}.
   */
  Optional<RecipeImportDto> getImportProvenance(UUID recipeId);

  /**
   * Return the persisted change-diff between two consecutive versions on the same branch. Pure
   * key-value lookup — no recompute. Throws {@code RecipeVersionNotFoundException} if either
   * version id is missing or {@code toVersion.recipeId} doesn't match {@code recipeId}; throws
   * {@code RecipeDiffCrossBranchException} if the two versions sit on different branches; throws
   * {@code RecipeDiffNotComputedException} if the two versions are non-consecutive on the same
   * branch.
   */
  RecipeDiffDto diff(UUID recipeId, UUID fromVersionId, UUID toVersionId);

  /**
   * All {@code ACCEPTED} substitutions for a recipe, sorted {@code last_applied_at DESC NULLS
   * LAST}. Verbatim from LLD line 531 ({@code getActiveSubstitutions}); the {@code state} predicate
   * uses the renamed value {@code ACCEPTED} per ticket 01e.
   */
  List<RecipeSubstitutionDto> getActiveSubstitutions(UUID recipeId);

  /**
   * All {@code ACCEPTED} substitutions on a specific version, sorted {@code last_applied_at DESC
   * NULLS LAST}. Verbatim from LLD line 532 ({@code getSubstitutionsForVersion}).
   */
  List<RecipeSubstitutionDto> getSubstitutionsForVersion(UUID versionId);

  /** Single-fetch helper used by the controller's GET-by-id read path. */
  Optional<RecipeSubstitutionDto> getSubstitution(UUID substitutionId);

  /**
   * Load a recipe version with its body and overlay the currently {@code ACCEPTED} substitutions
   * onto the result. The returned DTO carries the base version's id; the body lists reflect the
   * overlay; {@code appliedSubstitutionIds} lists the substitutions that contributed.
   */
  RecipeVersionDto getVersionWithSubstitutions(UUID recipeId, UUID versionId);

  /**
   * Cross-module accessor for the version's semantic embedding vector. Empty when the version is
   * missing, when the embedding column is still NULL (status {@code pending} or {@code failed}), or
   * when the embedding has zero dimensions. Future similarity-search consumers (recipe-01i +
   * planner) call through {@link com.example.mealprep.recipe.RecipeModule}. Per LLD line 541.
   *
   * <p>This is an in-process query helper, not a REST endpoint; missing versions return empty
   * rather than throwing {@code RecipeVersionNotFoundException}.
   */
  Optional<float[]> getEmbedding(UUID versionId);
}
