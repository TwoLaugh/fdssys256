package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.MergeSoftPreferencesRequest;
import com.example.mealprep.household.api.dto.MergedSoftPreferencesDto;
import com.example.mealprep.household.domain.service.HouseholdMergeService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the {@link HouseholdMergeService}. LLD §REST line 351 says merge is in-process
 * only; 01e adds REST anyway (symmetric with 01d's /current/members divergence) to make the merge
 * surface reachable before the planner module exists and to serve as a debug / admin path.
 */
@RestController
@Tag(name = "Households")
public class HouseholdMergeController {

  private final HouseholdQueryService queryService;
  private final HouseholdMergeService mergeService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdMergeController(
      HouseholdQueryService queryService,
      HouseholdMergeService mergeService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.mergeService = mergeService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/api/v1/households/current/merge",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Merge the soft-preferences of the calling user's household members (or a subset)."
              + " Read-only; not persisted.")
  public MergedSoftPreferencesDto merge(@Valid @RequestBody MergeSoftPreferencesRequest request) {
    UUID callerUserId = requireCurrentUserId();
    HouseholdDto household =
        queryService
            .getByUserId(callerUserId)
            .orElseThrow(() -> new HouseholdNotFoundException(callerUserId));

    List<UUID> requested = request.eaterUserIds();
    if (requested != null && !requested.isEmpty()) {
      Set<UUID> memberUserIds = new HashSet<>();
      for (HouseholdMemberDto m : household.members()) {
        memberUserIds.add(m.userId());
      }
      for (UUID u : requested) {
        if (!memberUserIds.contains(u)) {
          throw new InsufficientHouseholdRoleException(
              "eaterUserIds contains a user not in the caller's household: " + u);
        }
      }
    }
    return mergeService.mergeSoftPreferencesForSlot(household.id(), requested);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
