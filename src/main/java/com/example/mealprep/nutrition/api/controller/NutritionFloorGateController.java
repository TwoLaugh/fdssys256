package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator / integration-test side-door for the nutrition floor-gate. {@code POST
 * /api/v1/nutrition/floor-gate/evaluate} runs the gate for the calling user against a
 * candidate-plan rollup supplied in the request body.
 *
 * <p>The planner consumes {@link NutritionFloorGateService} directly (in-process); this endpoint
 * exists so operators and integration tests can exercise the gate without producing a plan. Always
 * returns HTTP 200 — {@code passed=false} is the actionable signal, not an error.
 */
@RestController
@RequestMapping("/api/v1/nutrition/floor-gate")
@Tag(name = "Nutrition")
public class NutritionFloorGateController {

  private static final Logger log = LoggerFactory.getLogger(NutritionFloorGateController.class);

  private final NutritionFloorGateService floorGateService;
  private final CurrentUserResolver currentUserResolver;

  public NutritionFloorGateController(
      NutritionFloorGateService floorGateService, CurrentUserResolver currentUserResolver) {
    this.floorGateService = floorGateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/evaluate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Evaluate the hard-floor gate for the calling user against a candidate plan rollup.")
  public ResponseEntity<FloorGateResultDto> evaluate(
      @Valid @RequestBody CandidatePlanRollupDto rollup) {
    UUID actorUserId = requireCurrentUserId();
    log.debug(
        "floor-gate evaluate requested actorUserId={} startDate={} endDate={} days={}",
        actorUserId,
        rollup.startDate(),
        rollup.endDate(),
        rollup.perDay().size());
    FloorGateResultDto result = floorGateService.evaluate(actorUserId, rollup);
    return ResponseEntity.ok(result);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
