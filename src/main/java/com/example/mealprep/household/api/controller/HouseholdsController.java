package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the household aggregate. Authentication is enforced by the auth module's
 * deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code userId}
 * server-side — the controller never accepts a {@code userId} from path or query, so user A cannot
 * create a household for user B.
 */
@RestController
@RequestMapping("/api/v1/households")
@Tag(name = "Households")
public class HouseholdsController {

  private final HouseholdQueryService queryService;
  private final HouseholdUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdsController(
      HouseholdQueryService queryService,
      HouseholdUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a new household; the calling user becomes its primary member.")
  public ResponseEntity<HouseholdDto> create(@Valid @RequestBody CreateHouseholdRequest request) {
    UUID creatorUserId = requireCurrentUserId();
    HouseholdDto created = updateService.createHousehold(creatorUserId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/households/" + created.id()))
        .body(created);
  }

  @GetMapping(path = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Return the calling user's household, or 404 if none.")
  public HouseholdDto getCurrent() {
    UUID userId = requireCurrentUserId();
    return queryService
        .getByUserId(userId)
        .orElseThrow(() -> new HouseholdNotFoundException(userId));
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
