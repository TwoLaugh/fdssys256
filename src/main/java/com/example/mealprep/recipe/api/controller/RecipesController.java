package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.api.dto.RecipeSearchCriteriaDto;
import com.example.mealprep.recipe.api.dto.UpdateRecipeManualEditRequest;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.exception.RecipeImportNotFoundException;
import com.example.mealprep.recipe.exception.RecipeNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * REST seam for the recipe aggregate. Authentication is enforced by the auth module's
 * deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code userId}
 * server-side. Read-by-id is open to any authenticated caller (planner / nutrition / hard-
 * constraint filter all need it); user-private filtering is enforced by the list/search read:
 * {@code GET /api/v1/recipes} returns only the caller's own {@code USER}-catalogue rows plus the
 * shared {@code SYSTEM} catalogue (recipe-list-search ticket — the recipes-page library grid).
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

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Paginated library list/search: caller-private USER rows + shared SYSTEM rows.",
      description =
          "Returns the caller's own USER-catalogue recipes plus the shared SYSTEM catalogue"
              + " (another user's USER rows never appear); soft-deleted rows are never returned"
              + " and archived rows only with includeArchived=true. minDataQuality is an ordinal"
              + " floor over USER_VERIFIED > IMPORTED ≈ AI_GENERATED > WEB_DISCOVERED"
              + " (IMPORTED and AI_GENERATED are tied: a floor at either admits both)."
              + " Sort is pinned updatedAt DESC. Rows carry the list-only avgTaste/ratingCount"
              + " aggregate (batched; null/0 when unrated).")
  public Page<RecipeDto> list(
      @RequestParam(required = false) Catalogue catalogue,
      @RequestParam(required = false) @Size(max = 160) String namePattern,
      @RequestParam(required = false) @Size(max = 64) String cuisine,
      @RequestParam(required = false) @Min(0) Integer maxTotalTimeMins,
      @RequestParam(required = false) DataQuality minDataQuality,
      @RequestParam(defaultValue = "false") boolean includeArchived,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    RecipeSearchCriteriaDto criteria =
        new RecipeSearchCriteriaDto(
            catalogue, namePattern, cuisine, maxTotalTimeMins, minDataQuality, includeArchived);
    return queryService.searchLibrary(userId, criteria, PageRequest.of(page, size));
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

  @PostMapping(path = "/{recipeId}/promote", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Promote a SYSTEM-catalogue recipe to the caller's USER catalogue (flip-in-place).")
  public RecipeDto promote(@PathVariable UUID recipeId) {
    UUID userId = requireCurrentUserId();
    return updateService.promoteToUserCatalogue(recipeId, userId);
  }

  @PostMapping(path = "/{recipeId}/demote")
  @Operation(
      summary =
          "Demote a USER-catalogue recipe owned by the caller to SYSTEM (flip-in-place; retains"
              + " userId for provenance).")
  public ResponseEntity<Void> demote(@PathVariable UUID recipeId) {
    UUID userId = requireCurrentUserId();
    updateService.demoteToSystemCatalogue(recipeId, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(path = "/{recipeId}/archive")
  @Operation(summary = "Soft-archive the recipe (sets archived_at); idempotent.")
  public ResponseEntity<Void> archive(@PathVariable UUID recipeId) {
    UUID userId = requireCurrentUserId();
    updateService.archive(recipeId, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(path = "/{recipeId}/unarchive")
  @Operation(summary = "Unarchive the recipe (clears archived_at); idempotent.")
  public ResponseEntity<Void> unarchive(@PathVariable UUID recipeId) {
    UUID userId = requireCurrentUserId();
    updateService.unarchive(recipeId, userId);
    return ResponseEntity.noContent().build();
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
