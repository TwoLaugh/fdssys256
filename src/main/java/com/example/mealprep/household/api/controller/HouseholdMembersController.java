package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.AddMemberRequest;
import com.example.mealprep.household.api.dto.ChangeRoleRequest;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.UpdateMemberRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the household member-administration endpoints. PRIMARY-only for add / update /
 * change-role; remove additionally permits self-remove.
 *
 * <p>{@code POST /api/v1/households/current/members} (direct add — bypasses the invite flow for
 * trusted callers), {@code PATCH /api/v1/households/current/members/{memberId}} (update {@code
 * priority} / {@code displayName} with PATCH semantics), {@code DELETE
 * /api/v1/households/current/members/{memberId}} (remove with last-primary guard), {@code POST
 * /api/v1/households/current/members/{memberId}/role} (role change with last-primary guard).
 *
 * <p>The actor is always server-resolved via {@link CurrentUserResolver} — the controller never
 * accepts a userId from path or query string. On {@code POST /current/members}, the request body's
 * {@code userId} is the TARGET being seated, not the actor.
 */
@RestController
@Tag(name = "Households")
public class HouseholdMembersController {

  private final HouseholdQueryService queryService;
  private final HouseholdUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdMembersController(
      HouseholdQueryService queryService,
      HouseholdUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/api/v1/households/current/members",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Directly add a member to the calling user's household (PRIMARY only). Prefer the"
              + " invite flow for user-facing onboarding.")
  public ResponseEntity<HouseholdMemberDto> add(@Valid @RequestBody AddMemberRequest request) {
    UUID callerUserId = requireCurrentUserId();
    UUID householdId = resolveCurrentHouseholdId(callerUserId);
    HouseholdMemberDto created = updateService.addMember(householdId, callerUserId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/households/current/members/" + created.id()))
        .body(created);
  }

  @PatchMapping(
      path = "/api/v1/households/current/members/{memberId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Update a member's priority / displayName (PRIMARY only). PATCH semantics — absent"
              + " fields are unchanged.")
  public HouseholdMemberDto update(
      @PathVariable UUID memberId, @Valid @RequestBody UpdateMemberRequest request) {
    UUID callerUserId = requireCurrentUserId();
    return updateService.updateMember(memberId, callerUserId, request);
  }

  @DeleteMapping(path = "/api/v1/households/current/members/{memberId}")
  @Operation(
      summary =
          "Remove a member from the calling user's household (PRIMARY removes anyone; members can"
              + " self-remove).")
  public ResponseEntity<Void> remove(@PathVariable UUID memberId) {
    UUID callerUserId = requireCurrentUserId();
    updateService.removeMember(memberId, callerUserId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      path = "/api/v1/households/current/members/{memberId}/role",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Change a member's role (PRIMARY only).")
  public HouseholdMemberDto changeRole(
      @PathVariable UUID memberId, @Valid @RequestBody ChangeRoleRequest request) {
    UUID callerUserId = requireCurrentUserId();
    return updateService.changeRole(memberId, callerUserId, request);
  }

  // ---------------- helpers ----------------

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  private UUID resolveCurrentHouseholdId(UUID callerUserId) {
    return queryService
        .getByUserId(callerUserId)
        .map(h -> h.id())
        .orElseThrow(() -> new HouseholdNotFoundException(callerUserId));
  }
}
