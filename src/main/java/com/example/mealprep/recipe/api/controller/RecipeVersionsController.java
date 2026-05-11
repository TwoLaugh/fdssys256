package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.RevertToVersionRequest;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe-version reads. recipe-01c only exposes the diff endpoint; recipe-01d appends
 * the revert flow.
 *
 * <p><b>LLD divergence — URL shape</b>: LLD §REST line 648 specifies the diff endpoint as a
 * query-param shape ({@code GET .../versions/diff?fromVersionId=&toVersionId=}). 01c uses path form
 * because the two version ids are part of the resource identity and the path form is trivially
 * cacheable on {@code (fromVersionId, toVersionId)} — the persisted {@code change_diff} is
 * immutable per LLD line 130's append-only rule.
 *
 * <p><b>LLD divergence — diff sourcing</b>: per LLD line 381 the diff is a key-value lookup, not a
 * recompute. 01c implements that strictly: cross-version (non-consecutive) diffs reject with {@code
 * RecipeDiffNotComputedException} (422); cross-branch diffs reject with {@code
 * RecipeDiffCrossBranchException} (422). 01d makes the cross-branch case reachable (users can now
 * create branches) but the 422 from 01c stands — cross-branch merge semantics are a 01f+ concern.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/versions")
@Tag(name = "Recipes")
public class RecipeVersionsController {

  private final RecipeQueryService queryService;
  private final RecipeUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeVersionsController(
      RecipeQueryService queryService,
      RecipeUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(
      path = "/{fromVersionId}/diff/{toVersionId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Return the persisted change_diff between two consecutive versions on the same branch.")
  public RecipeDiffDto diff(
      @PathVariable UUID recipeId,
      @PathVariable UUID fromVersionId,
      @PathVariable UUID toVersionId) {
    requireCurrentUserId();
    return queryService.diff(recipeId, fromVersionId, toVersionId);
  }

  @PostMapping(
      path = "/revert",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Revert a branch to an earlier version by writing a new version whose body clones the"
              + " target.")
  public RecipeVersionDto revert(
      @PathVariable UUID recipeId, @Valid @RequestBody RevertToVersionRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.revertToVersion(
        recipeId,
        request.branchId(),
        request.versionNumber(),
        userId,
        request.expectedRecipeOptimisticVersion());
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
