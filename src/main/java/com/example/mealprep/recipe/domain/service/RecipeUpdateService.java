package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.ConfirmImportRequest;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.ImportJobArchiveResult;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromHtmlRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportPreview;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import java.util.List;
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
   * Imports a recipe from a URL by fetching the page, running the shared {@code
   * RecipeExtractionService} pipeline, and persisting the recipe + a {@code RecipeImport}
   * provenance row atomically. Throws {@code RecipeImportFailureException} on fetch or extraction
   * failure.
   *
   * <p>One-shot variant retained for the existing {@code POST /imports/url} contract; the
   * preview-then-confirm pair ({@link #previewImportFromUrl} / {@link #confirmImport}) is the
   * Paprika-style flow the frontend uses. Both run dedup (recipe-2): a near-duplicate of an
   * existing library recipe throws {@code RecipeImportDuplicateException} (422 with the candidate
   * id).
   */
  RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request);

  /**
   * Preview an import from a URL (recipe-3 / LLD §Flow 2). Fetches the page, runs the shared {@code
   * RecipeExtractionService}, and returns the extracted candidate as an editable {@link
   * RecipeImportPreview} <b>without persisting anything</b>. A dedup probe against the caller's
   * library is included on the preview so the UI can warn before the user edits. Throws {@code
   * RecipeImportFailureException} (422) on fetch or extraction failure.
   */
  RecipeImportPreview previewImportFromUrl(UUID userId, ImportRecipeFromUrlRequest request);

  /**
   * Preview an import from frontend-supplied HTML (recipe-3 / LLD §Flow 2). Same as {@link
   * #previewImportFromUrl} but extraction runs against the supplied {@code html} (the in-app
   * browser already rendered the page); no server fetch is performed. Nothing is persisted.
   */
  RecipeImportPreview previewImportFromHtml(UUID userId, ImportRecipeFromHtmlRequest request);

  /**
   * Persist a previewed import the user has reviewed / edited (recipe-3 / LLD §Flow 2).
   * Re-validates and runs dedup (recipe-2) against the caller's library — a near-duplicate throws
   * {@code RecipeImportDuplicateException} (422 with the candidate id). Persists the recipe ({@code
   * dataQuality = IMPORTED}, {@code trigger = IMPORT}) + a {@code RecipeImport} provenance row
   * atomically and publishes {@code RecipeCreatedEvent} {@code AFTER_COMMIT}.
   */
  RecipeDto confirmImport(UUID userId, ConfirmImportRequest request);

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

  /**
   * Promote a SYSTEM-catalogue recipe into the calling user's USER catalogue (flip-in-place).
   * Plan-references / version IDs are preserved. Publishes {@code RecipePromotedEvent} {@code
   * AFTER_COMMIT}. Throws {@link com.example.mealprep.recipe.exception.RecipeNotFoundException}
   * (404) when missing, {@link
   * com.example.mealprep.recipe.exception.RecipeCatalogueViolationException} (422) when the recipe
   * is already USER, deleted, or archived. Per LLD line 563 / recipe-01g.
   */
  RecipeDto promoteToUserCatalogue(UUID systemRecipeId, UUID userId);

  /**
   * Demote a USER-catalogue recipe owned by the caller back to SYSTEM (flip-in-place); retains
   * {@code userId} for provenance. Publishes {@code RecipeArchivedEvent(cause=USER_DEMOTION)}
   * {@code AFTER_COMMIT}. Throws {@code RecipeNotFoundException} (404) when missing or not owned
   * (don't leak existence); {@code RecipeCatalogueViolationException} (422) when already SYSTEM.
   * Per LLD line 564 / recipe-01g.
   */
  void demoteToSystemCatalogue(UUID userRecipeId, UUID actorUserId);

  /**
   * Soft-archive a recipe — sets {@code archived_at = now()}. Idempotent (already archived → no
   * event). USER recipes: caller must own; SYSTEM recipes: any authenticated caller (v1 admin
   * policy). Already-deleted recipes → 422. Publishes {@code
   * RecipeArchivedEvent(cause=MANUAL_ADMIN)} {@code AFTER_COMMIT} on actual transition. Per LLD
   * line 565 / recipe-01g.
   */
  void archive(UUID recipeId, UUID actorUserId);

  /**
   * Clear {@code archived_at}. Idempotent (already unarchived → no-op). Same authorisation rules as
   * {@link #archive}. No event is published per LLD §Events. Per LLD line 566 / recipe-01g.
   */
  void unarchive(UUID recipeId, UUID actorUserId);

  /**
   * G11 graph-batch withdraw seam: soft-archive every SYSTEM-catalogue, non-deleted recipe whose
   * {@code recipe_imports} row carries the given {@code jobId} with {@code sourceType =
   * AI_GENERATED} (one {@code jobId} per graph batch is the G06 invariant; the source-type pin
   * keeps a graph withdraw from sweeping a discovery crawl that shares the {@code job_id} column).
   * Archive is the sanctioned exposure lever — {@code archived_at} is excluded from every plannable
   * read ({@code RecipeRepository} JPQL/native predicates), the row and its provenance survive
   * intact, and already-generated plans keep their slots (plans reference recipes; archive does not
   * touch them). Idempotent; atomic across the batch ({@code @Transactional}). Publishes {@code
   * RecipeArchivedEvent(cause=MANUAL_ADMIN)} per actual transition, mirroring {@link #archive}.
   * Caller (the discovery admin controller) is responsible for admin gating; {@code actorUserId} is
   * recorded in the audit log only — SYSTEM rows are archivable by any authenticated caller (v1
   * admin-open policy, {@code authoriseArchiveOp}).
   */
  ImportJobArchiveResult archiveByImportJobId(UUID jobId, UUID actorUserId);

  /**
   * Reversal of {@link #archiveByImportJobId} — clears {@code archived_at} on the same (jobId,
   * AI_GENERATED) match set: full restore, embeddings/ratings intact. Idempotent; no event
   * published (mirrors {@link #unarchive}).
   */
  ImportJobArchiveResult unarchiveByImportJobId(UUID jobId, UUID actorUserId);

  /**
   * Bulk-update {@code last_used_in_plan_at = now()} for the supplied recipe IDs. Empty list →
   * no-op. Unknown IDs tolerated. No event published. Consumed by the cook listener
   * (provisions-01g) and the future planner. Per LLD line 577 / recipe-01g.
   */
  void markUsedInPlan(List<UUID> recipeIds);
}
