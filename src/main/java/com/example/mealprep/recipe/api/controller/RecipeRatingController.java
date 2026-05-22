package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto;
import com.example.mealprep.recipe.api.dto.UpdateRatingRequest;
import com.example.mealprep.recipe.domain.service.RecipeRatingQueryService;
import com.example.mealprep.recipe.domain.service.RecipeRatingUpdateService;
import com.example.mealprep.recipe.exception.RecipeRatingNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for multi-dimensional recipe ratings (recipe-02b). All endpoints require
 * authentication; the caller's {@code userId} is resolved server-side via {@link
 * CurrentUserResolver} — never accepted from path or query. The {@code recipeId} path variable is
 * cross-checked against the request's {@code versionId} in the service.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/ratings")
@Tag(name = "Recipes")
public class RecipeRatingController {

  private final RecipeRatingQueryService queryService;
  private final RecipeRatingUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeRatingController(
      RecipeRatingQueryService queryService,
      RecipeRatingUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Record a rating on a recipe version.",
      description =
          "One-tap path supplies only taste; the detailed path adds the other three dimensions. "
              + "Fires RecipeRatingFiredEvent so the rating feeds taste-profile learning.")
  public ResponseEntity<RecipeRatingDto> create(
      @PathVariable UUID recipeId, @Valid @RequestBody CreateRatingRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeRatingDto created = updateService.create(userId, recipeId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + recipeId + "/ratings/" + created.id()))
        .body(created);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated list of ratings for a version, newest-first.")
  public Page<RecipeRatingDto> list(
      @PathVariable UUID recipeId,
      @RequestParam("versionId") UUID versionId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    requireCurrentUserId();
    return queryService.listByVersion(versionId, PageRequest.of(page, size));
  }

  @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "The caller's own rating for a version, if any.")
  public RecipeRatingDto mine(
      @PathVariable UUID recipeId, @RequestParam("versionId") UUID versionId) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getByVersionAndUser(versionId, userId)
        .orElseThrow(() -> new RecipeRatingNotFoundException(null));
  }

  @GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Aggregate rating summary for a version, or the whole recipe when versionId is omitted.")
  public RecipeRatingSummaryDto summary(
      @PathVariable UUID recipeId,
      @RequestParam(value = "versionId", required = false) UUID versionId) {
    requireCurrentUserId();
    return versionId != null
        ? queryService.getSummaryByVersion(versionId)
        : queryService.getSummaryByRecipe(recipeId);
  }

  @PutMapping(
      path = "/{ratingId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Revise an existing rating; recomputes the aggregate and re-fires the event.")
  public RecipeRatingDto update(
      @PathVariable UUID recipeId,
      @PathVariable UUID ratingId,
      @Valid @RequestBody UpdateRatingRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.update(userId, recipeId, ratingId, request);
  }

  @DeleteMapping(path = "/{ratingId}")
  @Operation(summary = "Delete the caller's rating.")
  public ResponseEntity<Void> delete(@PathVariable UUID recipeId, @PathVariable UUID ratingId) {
    UUID userId = requireCurrentUserId();
    updateService.delete(userId, recipeId, ratingId);
    return ResponseEntity.noContent().build();
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
