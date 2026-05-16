package com.example.mealprep.planner.api.controller;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST seam for the plan aggregate. 01a shipped {@code GET /{planId}}; 01c adds the
 * household-scoped read endpoints (active / history / range / suggestions). Write endpoints land
 * with planner-01j; per-suggestion dismiss with planner-01k.
 *
 * <p>Pagination params are bound as explicit {@code @RequestParam} ints (not via Spring's {@code
 * Pageable} argument resolver) so the {@code @Min/@Max} bounds attach to the visible API surface.
 * The {@code Pageable}'s {@code Sort} would be ignored anyway because the repo methods carry a
 * locked {@code OrderBy} clause per the LLD (sort order is part of the contract — frontend re-sort
 * is not supported).
 *
 * <p>Household-scoped authorisation deferred per LLD §Out of Scope — in v1 any authenticated caller
 * can read any household's plans; the auth module's deny-by-default chain enforces the {@code 401}
 * on missing cookies.
 */
@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans")
@Validated
public class PlansController {

  private final PlanQueryService planQueryService;

  public PlansController(PlanQueryService planQueryService) {
    this.planQueryService = planQueryService;
  }

  @GetMapping(path = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Fetch the currently ACTIVE plan for a household and week; 404 if none exists.")
  public ResponseEntity<PlanDto> getActive(
      @RequestParam UUID householdId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
    return planQueryService
        .getActivePlan(householdId, weekStartDate)
        .map(ResponseEntity::ok)
        .orElseThrow(
            () ->
                new PlanNotFoundException(
                    "no active plan for household " + householdId + " week " + weekStartDate));
  }

  @GetMapping(path = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Fetch all plan generations for a (household, weekStartDate); latest first; capped at"
              + " 100.")
  public ResponseEntity<List<PlanDto>> getHistory(
      @RequestParam UUID householdId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
    return ResponseEntity.ok(planQueryService.getPlanHistory(householdId, weekStartDate));
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Paginated list of plans for a household over [from, to] (inclusive); sorted by"
              + " weekStartDate DESC, generation DESC.")
  public ResponseEntity<Page<PlanDto>> getBetween(
      @RequestParam UUID householdId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok(
        planQueryService.getPlansBetween(householdId, from, to, PageRequest.of(page, size)));
  }

  @GetMapping(path = "/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List PENDING re-opt suggestions for a household, sorted by createdAt DESC.")
  public ResponseEntity<Page<ReoptSuggestionDto>> getSuggestions(
      @RequestParam UUID householdId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok(
        planQueryService.getPendingSuggestions(householdId, PageRequest.of(page, size)));
  }

  @GetMapping(path = "/{planId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Fetch a plan by id; returns the full hydrated aggregate (days + slots + scheduled"
              + " recipes).")
  public ResponseEntity<PlanDto> getPlan(@PathVariable UUID planId) {
    PlanDto plan =
        planQueryService.getPlanById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    return ResponseEntity.ok(plan);
  }
}
