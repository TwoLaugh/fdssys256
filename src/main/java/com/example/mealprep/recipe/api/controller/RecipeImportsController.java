package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.ConfirmImportRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromHtmlRequest;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeImportPreview;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe import endpoints. recipe-01b shipped the one-shot {@code POST /imports/url};
 * recipe-3 adds the Paprika-style preview-then-confirm flow (LLD §Flow 2): two preview endpoints
 * (URL + frontend-supplied HTML) that extract without persisting, and a confirm endpoint that
 * persists the (possibly user-edited) candidate. All three delegate extraction to the shared {@code
 * RecipeExtractionService} and run dedup (recipe-2) before persisting.
 */
@RestController
@RequestMapping("/api/v1/recipes/imports")
@Tag(name = "Recipes")
public class RecipeImportsController {

  private final RecipeUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeImportsController(
      RecipeUpdateService updateService, CurrentUserResolver currentUserResolver) {
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/url",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Import a recipe by fetching and parsing a URL (one-shot).")
  public ResponseEntity<RecipeDto> importFromUrl(
      @Valid @RequestBody ImportRecipeFromUrlRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeDto created = updateService.importFromUrl(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + created.id()))
        .body(created);
  }

  @PostMapping(
      path = "/preview-url",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Preview a URL import: fetch + extract the candidate without persisting (Paprika-style).")
  public RecipeImportPreview previewFromUrl(
      @Valid @RequestBody ImportRecipeFromUrlRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.previewImportFromUrl(userId, request);
  }

  @PostMapping(
      path = "/preview-html",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Preview an import from frontend-supplied HTML: extract the candidate without persisting.")
  public RecipeImportPreview previewFromHtml(
      @Valid @RequestBody ImportRecipeFromHtmlRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.previewImportFromHtml(userId, request);
  }

  @PostMapping(
      path = "/confirm",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Confirm a previewed import: persist the reviewed/edited candidate (runs dedup first).")
  public ResponseEntity<RecipeDto> confirmImport(@Valid @RequestBody ConfirmImportRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeDto created = updateService.confirmImport(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + created.id()))
        .body(created);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
