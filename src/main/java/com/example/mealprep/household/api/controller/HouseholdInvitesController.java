package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.AcceptInviteRequest;
import com.example.mealprep.household.api.dto.CreateInviteRequest;
import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for household invites. The {@link CurrentUserResolver} resolves the caller's {@code
 * userId} server-side; the controller never accepts a {@code userId} from path or body.
 *
 * <p>{@code POST /api/v1/households/current/invites} (PRIMARY-only create), {@code GET
 * /api/v1/households/current/invites} (list pending — codes redacted to {@code null}), {@code
 * DELETE /api/v1/households/current/invites/{inviteId}} (PRIMARY-only revoke), {@code POST
 * /api/v1/invites/accept} (the accept-by-code flow that lands the accepter as a {@code
 * HouseholdMember}).
 *
 * <p>The controller resolves the calling user's household id via {@link
 * HouseholdQueryService#getByUserId(UUID)} for list / create — the URL is {@code /current/invites}
 * (single-household-per-user invariant from 01a). Revoke does NOT pre-resolve the caller's
 * household here; the service performs the 404-ladder so an invite belonging to a different
 * household and a non-existent invite both surface as {@code household-invite-not- found}
 * (preventing existence leakage).
 */
@RestController
@Tag(name = "Households")
public class HouseholdInvitesController {

  private final HouseholdQueryService queryService;
  private final HouseholdUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdInvitesController(
      HouseholdQueryService queryService,
      HouseholdUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(
      path = "/api/v1/households/current/invites",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List the calling user's household's pending invites (codes redacted).")
  public List<HouseholdInviteDto> listPending() {
    UUID callerUserId = requireCurrentUserId();
    UUID householdId = resolveCurrentHouseholdId(callerUserId);
    return queryService.listPendingInvites(householdId);
  }

  @PostMapping(
      path = "/api/v1/households/current/invites",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create an invite for the calling user's household (PRIMARY only).")
  public ResponseEntity<HouseholdInviteDto> create(
      @Valid @RequestBody CreateInviteRequest request) {
    UUID callerUserId = requireCurrentUserId();
    UUID householdId = resolveCurrentHouseholdId(callerUserId);
    HouseholdInviteDto created = updateService.createInvite(householdId, callerUserId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/households/current/invites/" + created.id()))
        .body(created);
  }

  @DeleteMapping(path = "/api/v1/households/current/invites/{inviteId}")
  @Operation(summary = "Revoke a pending invite (PRIMARY only).")
  public ResponseEntity<Void> revoke(@PathVariable UUID inviteId) {
    UUID callerUserId = requireCurrentUserId();
    updateService.revokeInvite(inviteId, callerUserId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      path = "/api/v1/invites/accept",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Accept an invite by code; the accepter joins the inviting household.")
  public HouseholdMemberDto accept(@Valid @RequestBody AcceptInviteRequest request) {
    UUID accepterUserId = requireCurrentUserId();
    return updateService.acceptInvite(accepterUserId, request);
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
