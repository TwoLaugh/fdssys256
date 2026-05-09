package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the nutrition-targets aggregate. Authentication is enforced by the auth module's
 * deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code userId}
 * server-side — the controller never accepts a {@code userId} from path or query, so user A cannot
 * read or update user B's targets.
 */
@RestController
@RequestMapping("/api/v1/nutrition/targets")
@Tag(name = "Nutrition")
public class TargetsController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public TargetsController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Return the calling user's nutrition targets, or 404 if not yet initialised.")
  public TargetsDto get() {
    UUID userId = requireCurrentUserId();
    return queryService
        .getTargets(userId)
        .orElseThrow(() -> new NutritionTargetsNotFoundException(userId));
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the calling user's nutrition targets (full replacement).")
  public TargetsDto update(@Valid @RequestBody UpdateTargetsRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.updateTargets(userId, request, userId);
  }

  @GetMapping(path = "/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log of nutrition-targets changes; newest-first.")
  public Page<NutritionTargetsAuditEntryDto> auditLog(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getTargetsAuditLog(userId, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
