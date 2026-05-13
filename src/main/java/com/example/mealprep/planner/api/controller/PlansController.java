package com.example.mealprep.planner.api.controller;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST seam for the plan aggregate. 01a ships a single read endpoint; the write endpoints (POST
 * /generate, /accept, /reject, /abandon, /revert) land with planner-01j. Household-scoped
 * authorisation deferred per LLD §Out of Scope — in 01a any authenticated caller can read any plan;
 * the auth module's deny-by-default chain enforces the {@code 401} on missing cookies.
 */
@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans")
public class PlansController {

  private final PlanQueryService planQueryService;

  public PlansController(PlanQueryService planQueryService) {
    this.planQueryService = planQueryService;
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
