package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.AcceptDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.HealthDirectiveDto;
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.RejectDirectiveRequest;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.exception.HealthDirectiveNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * REST seam for the health-directives queue. Inbound, accept, reject and read endpoints all require
 * authentication; the caller's {@code userId} is resolved server-side via {@link
 * CurrentUserResolver}.
 *
 * <p>Inbound accepts directives where {@code userId} (the target user) may differ from the
 * authenticated caller — typical for clinician-portal scenarios. Accept / reject restrict to the
 * directive's target user (collapsed to 404 for any other caller).
 */
@RestController
@RequestMapping("/api/v1/nutrition/health-directives")
@Tag(name = "Nutrition")
public class HealthDirectivesController {

  private final NutritionQueryService queryService;
  private final NutritionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HealthDirectivesController(
      NutritionQueryService queryService,
      NutritionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List the caller's health directives.")
  public Page<HealthDirectiveDto> list(
      @RequestParam(required = false) DirectiveStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getDirectives(userId, status, pageable);
  }

  @GetMapping(path = "/{directiveId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a single directive.")
  public HealthDirectiveDto getById(@PathVariable UUID directiveId) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getDirective(userId, directiveId)
        .orElseThrow(() -> new HealthDirectiveNotFoundException(directiveId));
  }

  @PostMapping(
      path = "/inbound",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Health platform pushes a new directive (idempotent).")
  public ResponseEntity<HealthDirectiveDto> inbound(
      @Valid @RequestBody InboundHealthDirectiveRequest request) {
    UUID actorUserId = requireCurrentUserId();
    HealthDirectiveDto dto = updateService.receiveInboundDirective(actorUserId, request);
    return ResponseEntity.created(URI.create("/api/v1/nutrition/health-directives/" + dto.id()))
        .body(dto);
  }

  @PostMapping(
      path = "/{directiveId}/accept",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Accept a pending directive; runs safety gate then applies the deltas.")
  public HealthDirectiveDto accept(
      @PathVariable UUID directiveId, @Valid @RequestBody AcceptDirectiveRequest request) {
    UUID actorUserId = requireCurrentUserId();
    return updateService.acceptHealthDirective(actorUserId, directiveId, request);
  }

  @PostMapping(
      path = "/{directiveId}/reject",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Reject a pending directive; records the rejection reason.")
  public HealthDirectiveDto reject(
      @PathVariable UUID directiveId, @Valid @RequestBody RejectDirectiveRequest request) {
    UUID actorUserId = requireCurrentUserId();
    return updateService.rejectHealthDirective(actorUserId, directiveId, request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
