package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe-version reads. recipe-01c only exposes the diff endpoint; future tickets
 * layer version listing / revert.
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
 * RecipeDiffCrossBranchException} (422 — wired now, unreachable until 01d ships branches).
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/versions")
@Tag(name = "Recipes")
public class RecipeVersionsController {

  private final RecipeQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeVersionsController(
      RecipeQueryService queryService, CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
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

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
