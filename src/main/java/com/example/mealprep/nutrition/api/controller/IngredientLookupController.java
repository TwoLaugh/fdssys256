package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.CorrectIngredientMappingRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupResultDto;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.domain.service.internal.IngredientLookupInput;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMappingPipeline;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMappingResult;
import com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
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
 * REST seam for the ingredient-mapping cache. {@link CurrentUserResolver} resolves the calling
 * {@code userId} server-side (the cache itself is global / non-personalised, but the correction
 * endpoint records {@code actorUserId} for audit).
 *
 * <p>{@code /lookup} invokes the {@link IngredientMappingPipeline} (cache → USDA → OFF); {@code
 * /search} is cache-only (LIKE); {@code /correction} is a user-confirmed overwrite; {@code
 * /needs-review} pages the rows the pipeline flagged as low-confidence.
 */
@RestController
@RequestMapping("/api/v1/nutrition/ingredients")
@Tag(name = "Nutrition")
public class IngredientLookupController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final IngredientMappingPipeline pipeline;
  private final CurrentUserResolver currentUserResolver;

  public IngredientLookupController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      IngredientMappingPipeline pipeline,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.pipeline = pipeline;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/lookup", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Look up an ingredient (cache → USDA → OFF).")
  public ResponseEntity<IngredientNutritionDto> lookup(
      @RequestParam @NotBlank @Size(max = 255) String term) {
    requireCurrentUserId();
    IngredientMappingResult result = pipeline.resolve(new IngredientLookupInput(term, null));
    if (result instanceof IngredientMappingResult.Resolved resolved) {
      IngredientNutritionDto dto = resolved.dto();
      CacheControl cc =
          dto.lastVerifiedAt() == null ? CacheControl.noStore() : CacheControl.empty();
      return ResponseEntity.ok().cacheControl(cc).body(dto);
    }
    throw new IngredientMappingNotFoundException(term);
  }

  @PostMapping(
      path = "/search",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Cache-only LIKE search over ingredient mappings.")
  public IngredientLookupResultDto search(@Valid @RequestBody IngredientLookupRequest request) {
    requireCurrentUserId();
    return queryService.searchIngredientsForUi(request);
  }

  @PutMapping(
      path = "/{searchTerm}/correction",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Correct an ingredient mapping (bumps source=MANUAL, confidence=1.0).")
  public IngredientNutritionDto correct(
      @PathVariable String searchTerm,
      @Valid @RequestBody CorrectIngredientMappingRequest request) {
    UUID userId = requireCurrentUserId();
    String decoded = URLDecoder.decode(searchTerm, StandardCharsets.UTF_8);
    return updateService.correctIngredientMapping(
        decoded, request.override(), request.expectedVersion(), userId);
  }

  @GetMapping(path = "/needs-review", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated mappings flagged needs_review = true.")
  public Page<IngredientNutritionDto> needsReview(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getMappingsNeedingReview(pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
