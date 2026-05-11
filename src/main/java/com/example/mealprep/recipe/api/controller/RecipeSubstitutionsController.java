package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.AcceptSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.CreateSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.PromoteSubstitutionRequest;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.RejectSubstitutionRequest;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for recipe substitutions per ticket recipe-01e.
 *
 * <p>Five endpoints: propose (POST /), list-active (GET /active), list-by-version (GET
 * /?versionId=...), the discriminated lifecycle endpoint (POST /{subId}/{action}; action is one of
 * accept/reject/promote-to-version), and the version-with-overlays read (GET
 * /versions/{versionId}/with-substitutions — handled here for routing convenience even though it
 * doesn't sit under /substitutions).
 */
@RestController
@RequestMapping("/api/v1/recipes/{recipeId}")
@Tag(name = "Recipes")
public class RecipeSubstitutionsController {

  private static final String ACTION_ACCEPT = "accept";
  private static final String ACTION_REJECT = "reject";
  private static final String ACTION_PROMOTE = "promote-to-version";

  private final RecipeQueryService queryService;
  private final RecipeUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;
  private final ObjectMapper objectMapper;

  public RecipeSubstitutionsController(
      RecipeQueryService queryService,
      RecipeUpdateService updateService,
      CurrentUserResolver currentUserResolver,
      ObjectMapper objectMapper) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
    this.objectMapper = objectMapper;
  }

  @GetMapping(path = "/substitutions/active", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List all ACCEPTED substitutions for the recipe.")
  public List<RecipeSubstitutionDto> listActive(@PathVariable UUID recipeId) {
    requireCurrentUserId();
    return queryService.getActiveSubstitutions(recipeId);
  }

  @GetMapping(path = "/substitutions", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List substitutions for a specific version (state filter: ACCEPTED).")
  public List<RecipeSubstitutionDto> listForVersion(
      @PathVariable UUID recipeId, @RequestParam("versionId") UUID versionId) {
    requireCurrentUserId();
    return queryService.getSubstitutionsForVersion(versionId);
  }

  @PostMapping(
      path = "/substitutions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Propose a new substitution; state = PROPOSED.")
  public ResponseEntity<RecipeSubstitutionDto> create(
      @PathVariable UUID recipeId, @Valid @RequestBody CreateSubstitutionRequest request) {
    UUID userId = requireCurrentUserId();
    RecipeSubstitutionDto created = updateService.createSubstitution(recipeId, request, userId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/recipes/" + recipeId + "/substitutions/" + created.id()))
        .body(created);
  }

  @PostMapping(
      path = "/substitutions/{subId}/{action}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Discriminated lifecycle action — single endpoint with action in path: accept | reject | promote-to-version.")
  public ResponseEntity<?> act(
      @PathVariable UUID recipeId,
      @PathVariable UUID subId,
      @PathVariable String action,
      @RequestBody JsonNode body) {
    UUID userId = requireCurrentUserId();
    switch (action) {
      case ACTION_ACCEPT -> {
        AcceptSubstitutionRequest req = readBody(body, AcceptSubstitutionRequest.class);
        validate(req);
        RecipeSubstitutionDto result =
            updateService.acceptSubstitution(subId, userId, req.expectedVersion());
        return ResponseEntity.ok(result);
      }
      case ACTION_REJECT -> {
        RejectSubstitutionRequest req = readBody(body, RejectSubstitutionRequest.class);
        validate(req);
        RecipeSubstitutionDto result =
            updateService.rejectSubstitution(subId, userId, req.expectedVersion(), req.reason());
        return ResponseEntity.ok(result);
      }
      case ACTION_PROMOTE -> {
        PromoteSubstitutionRequest req = readBody(body, PromoteSubstitutionRequest.class);
        validate(req);
        RecipeVersionDto result =
            updateService.promoteSubstitutionToVersion(
                subId, userId, req.expectedVersion(), req.changeReason());
        return ResponseEntity.ok(result);
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Unknown substitution action: " + action);
    }
  }

  @GetMapping(
      path = "/versions/{versionId}/with-substitutions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Fetch a version with active substitutions overlaid onto the ingredients / method.")
  public RecipeVersionDto getVersionWithSubstitutions(
      @PathVariable UUID recipeId, @PathVariable UUID versionId) {
    requireCurrentUserId();
    return queryService.getVersionWithSubstitutions(recipeId, versionId);
  }

  private <T> T readBody(JsonNode body, Class<T> type) {
    try {
      return objectMapper.treeToValue(body, type);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body", ex);
    }
  }

  private void validate(AcceptSubstitutionRequest req) {
    if (req == null || req.expectedVersion() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedVersion must be >= 0");
    }
  }

  private void validate(RejectSubstitutionRequest req) {
    if (req == null || req.expectedVersion() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedVersion must be >= 0");
    }
    if (req.reason() != null && req.reason().length() > 255) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason exceeds 255 characters");
    }
  }

  private void validate(PromoteSubstitutionRequest req) {
    if (req == null || req.expectedVersion() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedVersion must be >= 0");
    }
    if (req.changeReason() == null || req.changeReason().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "changeReason is required");
    }
    if (req.changeReason().length() > 2000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "changeReason exceeds 2000 chars");
    }
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
