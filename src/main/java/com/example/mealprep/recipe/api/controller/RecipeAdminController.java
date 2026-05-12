package com.example.mealprep.recipe.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.recipe.api.dto.RunArchiveScanResultDto;
import com.example.mealprep.recipe.domain.service.internal.ArchiveEligibilityScanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only seam for the recipe aggregate. Today exposes only the manual trigger for the daily
 * archive-eligibility scan (per LLD line 656 + recipe-01g ticket §Admin trigger). Authentication is
 * enforced by the auth chain; v1 is open to any authenticated caller — a future ticket adds an
 * {@code ADMIN}-role gate when the role enum acquires that constant.
 */
@RestController
@RequestMapping("/api/v1/recipes/admin")
@Tag(name = "Recipes")
public class RecipeAdminController {

  private final ArchiveEligibilityScanner scanner;
  private final CurrentUserResolver currentUserResolver;

  public RecipeAdminController(
      ArchiveEligibilityScanner scanner, CurrentUserResolver currentUserResolver) {
    this.scanner = scanner;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(path = "/run-archive-scan", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Manually trigger the daily archive-eligibility scan; returns flaggedCount of newly"
              + " archived rows.")
  public RunArchiveScanResultDto runArchiveScan() {
    requireCurrentUserId();
    int flagged = scanner.runOnce();
    return new RunArchiveScanResultDto(flagged);
  }

  private void requireCurrentUserId() {
    currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
