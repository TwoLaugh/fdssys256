package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import java.util.UUID;

/**
 * Write-side contract for the recipe module.
 *
 * <p>01a only ships the {@code manual_create} trigger; recipe-01b appends the URL import flow
 * ({@link #importFromUrl}) which fetches + parses + persists in a single transaction with {@code
 * dataQuality = IMPORTED} and {@code trigger = IMPORT}.
 *
 * <p>recipe-01e appends the substitution write paths: {@link #createSubstitution}, {@link
 * #acceptSubstitution}, {@link #rejectSubstitution}, {@link #promoteSubstitutionToVersion}.
 *
 * <p><b>LLD divergence note</b>: the LLD's {@code deactivateSubstitution} (line 573) is dropped in
 * favour of accept / reject with the renamed state-machine values (see ticket 01e for the
 * rationale).
 */
public interface RecipeUpdateService {

  /**
   * Creates a {@code Recipe} aggregate root with its main branch and v1 body in a single
   * transaction. Publishes {@code RecipeCreatedEvent} and {@code RecipeVersionCreatedEvent} {@code
   * AFTER_COMMIT}.
   */
  RecipeDto createRecipe(UUID userId, CreateRecipeRequest request);

  /**
   * Imports a recipe from a URL by fetching the page, running the deterministic {@code
   * HtmlImportParser}, and persisting the recipe + a {@code RecipeImport} provenance row
   * atomically. Throws {@code RecipeImportFailureException} on fetch or extraction failure.
   */
  RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request);

  /**
   * Apply a manual edit to a recipe. Inserts a new {@code RecipeVersion} (v2+) on the recipe's
   * current branch, leaves the old version's body rows untouched, advances {@code
   * Recipe.currentVersion} and {@code RecipeBranch.currentVersion}, and publishes both {@code
   * RecipeVersionCreatedEvent} and {@code RecipeUpdatedEvent} {@code AFTER_COMMIT}.
   *
   * <p>Throws {@code RecipeNotFoundException} (404) if the recipe is missing, soft-deleted, or
   * owned by a different user; {@code RecipeCatalogueViolationException} (422) on a SYSTEM recipe;
   * {@code OptimisticLockingFailureException} (409) on stale {@code expectedOptimisticVersion};
   * {@code NoChangesException} (400) if the edit is a no-op.
   */
  RecipeDto manualEdit(UUID recipeId, UpdateRecipeManualEditRequest request, UUID actorUserId);

  /**
   * Fork a recipe into a new branch off a specific version. Inserts a new {@code RecipeBranch} row
   * (with provisional jaccard-mean {@code divergenceScore}) and a new v1 {@code RecipeVersion} on
   * that branch (trigger = {@code BRANCH_CREATION}), with the body cloned from the request's {@code
   * body} sub-block. Does NOT mutate {@code Recipe.currentBranchId} — branch checkout is a separate
   * flow (deferred to recipe-01g). Publishes both {@code RecipeVersionCreatedEvent} and the new
   * {@code RecipeBranchCreatedEvent} {@code AFTER_COMMIT}.
   *
   * <p><b>LLD divergence</b>: LLD §RecipeUpdateService (lines 549-578) only lists {@code
   * saveAdaptedBranch} on the SPI; 01d adds the user-facing variant. The pipeline-driven branch
   * creation lands with recipe-01f.
   *
   * <p>Throws {@code RecipeNotFoundException} (404) for missing/soft-deleted/foreign-owned recipes,
   * {@code RecipeCatalogueViolationException} (422) on SYSTEM recipes, {@code
   * RecipeBranchPointInvalidException} (422) when the branch-point version doesn't resolve to the
   * parent recipe, {@code RecipeBranchNameReservedException} (422) for {@code "main"}, and {@code
   * RecipeBranchNameConflictException} (409) when the name is already taken on this recipe.
   */
  RecipeBranchDto createBranch(UUID recipeId, CreateBranchRequest request, UUID actorUserId);

  /**
   * Revert a branch to an earlier version by writing a new version row whose body clones the target
   * version. {@code trigger = REVERT}; {@code parentVersionId} is set to the current version's id
   * (not the target's) so the genealogy reflects the move. {@code Recipe.currentVersion} and {@code
   * RecipeBranch.currentVersion} are bumped to the new row.
   *
   * <p>Publishes BOTH {@code RecipeVersionCreatedEvent} and {@code RecipeUpdatedEvent} ({@code
   * trigger = REVERT}) {@code AFTER_COMMIT}, symmetric with manual-edit.
   *
   * <p>Throws the same exceptions as {@link #manualEdit}, plus {@code
   * RecipeBranchNotFoundException} if the branch is missing / belongs to a different recipe, and
   * {@code RecipeVersionNotFoundException} if the target version number doesn't exist on the
   * branch. Throws {@code NoChangesException} (400) when the target is already the branch's current
   * version.
   */
  RecipeVersionDto revertToVersion(
      UUID recipeId,
      UUID branchId,
      int versionNumber,
      UUID actorUserId,
      long expectedRecipeOptimisticVersion);

  /**
   * Propose a new substitution on a recipe version. Inserts a {@code RecipeSubstitution} row with
   * {@code state = PROPOSED} and publishes {@code RecipeSubstitutionCreatedEvent} {@code
   * AFTER_COMMIT}.
   */
  RecipeSubstitutionDto createSubstitution(
      UUID recipeId, CreateSubstitutionRequest request, UUID actorUserId);

  /**
   * Move a substitution to {@code ACCEPTED}. No-op (200, no event) if already accepted; 422 if the
   * substitution is in a terminal state. Publishes {@code RecipeSubstitutionStateChangedEvent} on
   * actual transitions.
   */
  RecipeSubstitutionDto acceptSubstitution(
      UUID substitutionId, UUID actorUserId, long expectedVersion);

  /**
   * Move a substitution to {@code REJECTED}. No-op (200, no event) if already rejected; 422 if the
   * substitution is in a terminal state. The {@code reason} is logged at INFO for audit but not
   * persisted on the row.
   */
  RecipeSubstitutionDto rejectSubstitution(
      UUID substitutionId, UUID actorUserId, long expectedVersion, String reason);

  /**
   * Promote an {@code ACCEPTED} substitution into a new {@code RecipeVersion} on the same branch.
   * Inserts a new version row with the substitution's swap applied; bumps {@code
   * Recipe.currentVersion} and {@code RecipeBranch.currentVersion}; moves the substitution to
   * {@code SUPERSEDED}.
   *
   * <p>Publishes {@code RecipeVersionCreatedEvent}, {@code RecipeUpdatedEvent} ({@code trigger =
   * SUBSTITUTION_PROMOTION}), and {@code RecipeSubstitutionStateChangedEvent} {@code AFTER_COMMIT}.
   * Returns the hydrated new version DTO.
   */
  RecipeVersionDto promoteSubstitutionToVersion(
      UUID substitutionId, UUID actorUserId, long expectedVersion, String changeReason);
}
