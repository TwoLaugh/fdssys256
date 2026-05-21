package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.recipe.api.dto.CreateBranchRequest;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.exception.RecipeBranchNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe branches. recipe-01b shipped the list endpoint; recipe-01d adds the
 * get-by-id and the user-facing branch creation flow.
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}/branches")
@Tag(name = "Recipes")
public class RecipeBranchesController {

  private final RecipeQueryService queryService;
  private final RecipeUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public RecipeBranchesController(
      RecipeQueryService queryService,
      RecipeUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List a recipe's branches (always at least 'main').")
  @BoundedCollection("bounded by recipe; branches per recipe are typically < 10")
  public List<RecipeBranchDto> list(@PathVariable UUID recipeId) {
    requireCurrentUserId();
    return queryService.getBranches(recipeId);
  }

  @GetMapping(path = "/{branchId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a single branch by id.")
  public RecipeBranchDto getOne(@PathVariable UUID recipeId, @PathVariable UUID branchId) {
    requireCurrentUserId();
    return queryService
        .getBranch(recipeId, branchId)
        .orElseThrow(() -> new RecipeBranchNotFoundException(branchId));
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fork a recipe into a new branch off a specific version.")
  public ResponseEntity<RecipeBranchDto> create(
      @PathVariable UUID recipeId, @Valid @RequestBody CreateBranchRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeBranchDto created = updateService.createBranch(recipeId, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + recipeId + "/branches/" + created.id()))
        .body(created);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
