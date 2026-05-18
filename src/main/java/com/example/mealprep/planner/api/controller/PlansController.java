package com.example.mealprep.planner.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.planner.api.dto.AbandonPlanRequest;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.RejectPlanRequest;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.SlotStateChangeRequest;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.domain.service.PlanWriteService;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.security.PlannerAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
  private final PlanWriteService planWriteService;
  private final PlanComposer planComposer;
  private final PlannerAuth plannerAuth;
  private final CurrentUserResolver currentUserResolver;

  public PlansController(
      PlanQueryService planQueryService,
      PlanWriteService planWriteService,
      PlanComposer planComposer,
      PlannerAuth plannerAuth,
      CurrentUserResolver currentUserResolver) {
    this.planQueryService = planQueryService;
    this.planWriteService = planWriteService;
    this.planComposer = planComposer;
    this.plannerAuth = plannerAuth;
    this.currentUserResolver = currentUserResolver;
  }

  // ============================================================================================
  // Write endpoints (planner-01j)
  // ============================================================================================

  @PostMapping(
      path = "/generate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Compose + persist a new plan (Stage A->D). 201 + Location; 200 + cached body on an"
              + " Idempotency-Key replay.")
  public ResponseEntity<PlanDto> generate(
      @Valid @RequestBody GeneratePlanRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    UUID userId = requireUser();
    if (!plannerAuth.canAccessHousehold(userId, request.householdId())) {
      throw forbidden();
    }
    Optional<UUID> cached = planComposer.cachedPlanIdFor(userId, idempotencyKey);
    if (cached.isPresent()) {
      PlanDto dto =
          planQueryService
              .getPlanById(cached.get())
              .orElseThrow(() -> new PlanNotFoundException(cached.get()));
      return ResponseEntity.ok(dto);
    }
    UUID newPlanId = planComposer.compose(request, userId, idempotencyKey);
    PlanDto dto =
        planQueryService
            .getPlanById(newPlanId)
            .orElseThrow(() -> new PlanNotFoundException(newPlanId));
    return ResponseEntity.created(URI.create("/api/v1/plans/" + newPlanId)).body(dto);
  }

  @PostMapping(path = "/{planId}/accept", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Accept a GENERATED plan (-> ACTIVE). 200 + PlanDto.")
  public ResponseEntity<PlanDto> accept(@PathVariable UUID planId) {
    authPlan(planId);
    planWriteService.acceptPlan(planId);
    return ResponseEntity.ok(reload(planId));
  }

  @PostMapping(
      path = "/{planId}/reject",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Reject a GENERATED plan (-> REJECTED). Idempotent. 200 + PlanDto.")
  public ResponseEntity<PlanDto> reject(
      @PathVariable UUID planId, @Valid @RequestBody RejectPlanRequest body) {
    authPlan(planId);
    planWriteService.rejectPlan(planId, body.reason());
    return ResponseEntity.ok(reload(planId));
  }

  @PostMapping(
      path = "/{planId}/abandon",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Abandon an ACTIVE plan (-> ABANDONED). 200 + PlanDto.")
  public ResponseEntity<PlanDto> abandon(
      @PathVariable UUID planId, @Valid @RequestBody AbandonPlanRequest body) {
    authPlan(planId);
    planWriteService.abandonPlan(planId, body.reason());
    return ResponseEntity.ok(reload(planId));
  }

  @PostMapping(path = "/{planId}/revert", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Revert: supersede an ACTIVE plan and create a fresh GENERATED generation copy."
              + " 201 + new PlanDto.")
  public ResponseEntity<PlanDto> revert(@PathVariable UUID planId) {
    authPlan(planId);
    UUID newPlanId = planWriteService.revertPlan(planId);
    PlanDto dto =
        planQueryService
            .getPlanById(newPlanId)
            .orElseThrow(() -> new PlanNotFoundException(newPlanId));
    return ResponseEntity.created(URI.create("/api/v1/plans/" + newPlanId)).body(dto);
  }

  @PatchMapping(
      path = "/{planId}/slots/{slotId}/state",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Transition a single slot's state. 200 + PlanDto.")
  public ResponseEntity<PlanDto> changeSlotState(
      @PathVariable UUID planId,
      @PathVariable UUID slotId,
      @Valid @RequestBody SlotStateChangeRequest body) {
    authPlan(planId);
    planWriteService.changeSlotState(planId, slotId, body.newState());
    return ResponseEntity.ok(reload(planId));
  }

  @PostMapping(
      path = "/{planId}/reopt-suggestions/{suggestionId}/accept",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Accept a mid-week re-opt suggestion: applies it onto a new generation."
              + " 200 + suggestion.")
  public ResponseEntity<PlanReoptSuggestionDto> acceptReoptSuggestion(
      @PathVariable UUID planId, @PathVariable UUID suggestionId) {
    authPlan(planId);
    return ResponseEntity.ok(planWriteService.acceptReoptSuggestion(planId, suggestionId));
  }

  @PostMapping(
      path = "/{planId}/reopt-suggestions/{suggestionId}/reject",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Reject a mid-week re-opt suggestion. No plan change. 200 + suggestion.")
  public ResponseEntity<PlanReoptSuggestionDto> rejectReoptSuggestion(
      @PathVariable UUID planId, @PathVariable UUID suggestionId) {
    authPlan(planId);
    return ResponseEntity.ok(planWriteService.rejectReoptSuggestion(planId, suggestionId));
  }

  // ---- auth helpers --------------------------------------------------------------------------

  private UUID requireUser() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }

  /** 401 if anon; 404 if the plan doesn't exist; 403 if the caller is not in its household. */
  private void authPlan(UUID planId) {
    UUID userId = requireUser();
    planQueryService.getPlanById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    if (!plannerAuth.canAccessPlan(userId, planId)) {
      throw forbidden();
    }
  }

  private PlanDto reload(UUID planId) {
    return planQueryService
        .getPlanById(planId)
        .orElseThrow(() -> new PlanNotFoundException(planId));
  }

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Caller is not a member of the plan's household");
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
