package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.api.AdminAccessGuard;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRequest;
import com.example.mealprep.nutrition.domain.service.IngredientMappingSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * G05 (graph integration): admin seed path for {@code nutrition_ingredient_mapping} — NON-e2e; a
 * production admin surface gated by the shared {@link AdminAccessGuard} (config {@code
 * mealprep.admin.user-ids}), same imperative pattern as {@code DiscoveryAdminController}. Anonymous
 * ⇒ 401, authenticated-but-not-allowlisted ⇒ 403, fail-closed on an empty allowlist.
 *
 * <p>The body is the spike-side seed artifact ({@code ingredient_mapping_seed.json}) as-is.
 * Semantics are idempotent first-writer-wins; a collision reports {@code FAILED} + HTTP 409 and the
 * caller (runbook, G06 pre-flight) must treat it as a hard stop.
 */
@RestController
@RequestMapping("/api/v1/nutrition/admin/ingredient-mappings")
@Tag(name = "Nutrition")
public class NutritionAdminMappingSeedController {

  private final IngredientMappingSeedService seedService;
  private final AdminAccessGuard adminGuard;

  public NutritionAdminMappingSeedController(
      IngredientMappingSeedService seedService, AdminAccessGuard adminGuard) {
    this.seedService = seedService;
    this.adminGuard = adminGuard;
  }

  @PostMapping(
      path = "/seed",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Admin: seed the ingredient-mapping cache from the spike-canon artifact (G05)."
              + " Idempotent, first-writer-wins; any collision => status FAILED + 409, existing"
              + " rows never overwritten.")
  public ResponseEntity<IngredientMappingSeedReport> seed(
      @Valid @RequestBody IngredientMappingSeedRequest request) {
    adminGuard.requireAdmin();
    IngredientMappingSeedReport report = seedService.seed(request);
    HttpStatus status =
        IngredientMappingSeedReport.STATUS_FAILED.equals(report.status())
            ? HttpStatus.CONFLICT
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(report);
  }
}
