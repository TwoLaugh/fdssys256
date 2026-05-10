package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe branch listings. recipe-01b only exposes the read endpoint; branch creation
 * lands with recipe-01d.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/branches")
@Tag(name = "Recipes")
public class RecipeBranchesController {

  private final RecipeQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeBranchesController(
      RecipeQueryService queryService, CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List a recipe's branches (always at least 'main').")
  public List<RecipeBranchDto> list(@PathVariable UUID recipeId) {
    requireCurrentUserId();
    return queryService.getBranches(recipeId);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
