package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.ImportRecipeFromUrlRequest;
import com.example.mealprep.recipe.api.dto.RecipeDto;
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
 * REST seam for recipe import endpoints. recipe-01b ships only the one-shot {@code POST
 * /imports/url} (per the LLD §REST table); the preview-then-confirm flow stays in {@code Flow 2}
 * until the frontend in-app browser ticket lands.
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
  @Operation(summary = "Import a recipe by fetching and parsing a URL.")
  public ResponseEntity<RecipeDto> importFromUrl(
      @Valid @RequestBody ImportRecipeFromUrlRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeDto created = updateService.importFromUrl(userId, request);
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
