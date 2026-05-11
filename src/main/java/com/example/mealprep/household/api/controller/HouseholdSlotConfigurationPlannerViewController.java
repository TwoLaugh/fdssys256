package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the 01f planner-facing slot-configuration view. Same {@code /current/...} pattern
 * as 01e's {@code HouseholdMergeController}: the server resolves the caller, then their household.
 * Any authenticated household member can call (read-only, no role gate).
 */
@RestController
@Tag(name = "Households")
public class HouseholdSlotConfigurationPlannerViewController {

  private final HouseholdQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdSlotConfigurationPlannerViewController(
      HouseholdQueryService queryService, CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(
      path = "/api/v1/households/current/slot-configuration/planner-view",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Planner-friendly slot-configuration view for the calling user's household;"
              + " read-only, not persisted.")
  public SlotConfigurationPlannerViewDto get() {
    UUID callerUserId = requireCurrentUserId();
    HouseholdDto household =
        queryService
            .getByUserId(callerUserId)
            .orElseThrow(() -> new HouseholdNotFoundException(callerUserId));
    return queryService.getSlotConfigurationPlannerView(household.id());
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
