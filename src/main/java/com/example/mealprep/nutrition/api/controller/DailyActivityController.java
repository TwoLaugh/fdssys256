package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.UpsertDailyActivityRequest;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for daily-activity reads + upsert. Mounted under {@code
 * /api/v1/nutrition/targets/activity} per the LLD's TargetsController split. Server resolves {@code
 * userId} via {@link CurrentUserResolver}; never accepted from path / query.
 */
@RestController
@RequestMapping("/api/v1/nutrition/targets/activity")
@Tag(name = "Nutrition")
public class DailyActivityController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public DailyActivityController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List daily activity log entries in [from, to].")
  @BoundedCollection("bounded by date range; one row per day")
  public List<DailyActivityDto> getRange(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    UUID userId = requireCurrentUserId();
    return queryService.getDailyActivityRange(userId, from, to);
  }

  @PutMapping(
      path = "/{date}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Upsert the activity entry for a date.")
  public DailyActivityDto upsert(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody UpsertDailyActivityRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.upsertDailyActivity(
        userId, date, request.activityLevel(), request.notes());
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
