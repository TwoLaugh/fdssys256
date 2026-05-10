package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.exception.RecipeImportNotFoundException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the recipe aggregate. Authentication is enforced by the auth module's
 * deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code userId}
 * server-side. Read-by-id is open to any authenticated caller (planner / nutrition / hard-
 * constraint filter all need it); user-private filtering belongs in search/list endpoints later.
 *
 * <p>recipe-01c adds {@code PUT /api/v1/recipes/{recipeId}} (manual edit) — creates a new {@code
 * RecipeVersion} (v2+) on the recipe's current branch with {@code trigger = MANUAL_EDIT} and the
 * computed {@code change_diff}. Authorisation: caller must own the recipe; SYSTEM-catalogue recipes
 * are rejected with 422 {@code recipe-catalogue-violation} (the user must promote to USER first —
 * promotion is recipe-01g).
 */
@RestController
@RequestMapping("/api/v1/recipes")
@Tag(name = "Recipes")
public class RecipesController {

  private final RecipeQueryService queryService;
  private final RecipeUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public RecipesController(
      RecipeQueryService queryService,
      RecipeUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a new user-catalogue recipe (manual_create trigger).")
  public ResponseEntity<RecipeDto> create(@Valid @RequestBody CreateRecipeRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeDto created = updateService.createRecipe(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + created.id()))
        .body(created);
  }

  @GetMapping(path = "/{recipeId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a recipe by id (current version body).")
  public RecipeDto getById(@PathVariable UUID recipeId) {
    requireCurrentUserId();
    return queryService.getById(recipeId).orElseThrow(() -> new RecipeNotFoundException(recipeId));
  }

  @PutMapping(
      path = "/{recipeId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Manually edit a recipe; creates a new RecipeVersion (v2+) on the current branch.")
  public RecipeDto manualEdit(
      @PathVariable UUID recipeId, @Valid @RequestBody UpdateRecipeManualEditRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.manualEdit(recipeId, request, userId);
  }

  @GetMapping(path = "/{recipeId}/import-provenance", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Return import provenance (source URL, extraction method, raw payload) for a recipe.")
  public RecipeImportDto getImportProvenance(@PathVariable UUID recipeId) {
    requireCurrentUserId();
    // 404 with type=recipe-not-found if the recipe itself is missing or soft-deleted.
    queryService.getById(recipeId).orElseThrow(() -> new RecipeNotFoundException(recipeId));
    return queryService
        .getImportProvenance(recipeId)
        .orElseThrow(() -> new RecipeImportNotFoundException(recipeId));
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
