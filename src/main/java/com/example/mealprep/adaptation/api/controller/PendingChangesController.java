package com.example.mealprep.adaptation.api.controller;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.api.mapper.PendingChangeMapper;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.core.api.markers.BoundedCollection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public REST surface for the user-facing pending-change lifecycle. Five endpoints per LLD lines
 * 585-595.
 *
 * <p>Authentication is enforced by {@code AuthSecurityConfig}'s deny-by-default chain; {@link
 * CurrentUserResolver} resolves the caller's {@code userId} server-side — the controller never
 * accepts a {@code userId} from a path or query param, so user A cannot read or mutate user B's
 * pending changes.
 */
@RestController
@RequestMapping("/api/v1/adaptation")
@Tag(name = "Adaptation")
public class PendingChangesController {

  private final AdaptationService adaptationService;
  private final AdaptationQueryService queryService;
  private final PendingChangeRepository pendingChangeRepository;
  private final PendingChangeMapper pendingChangeMapper;
  private final CurrentUserResolver currentUserResolver;

  public PendingChangesController(
      AdaptationService adaptationService,
      AdaptationQueryService queryService,
      PendingChangeRepository pendingChangeRepository,
      PendingChangeMapper pendingChangeMapper,
      CurrentUserResolver currentUserResolver) {
    this.adaptationService = adaptationService;
    this.queryService = queryService;
    this.pendingChangeRepository = pendingChangeRepository;
    this.pendingChangeMapper = pendingChangeMapper;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/pending-changes", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Top-3 ranked pending changes for the authenticated user.")
  @BoundedCollection("explicit top-3 cap per ranking algorithm")
  public List<PendingChangeListItemDto> listForUser() {
    UUID userId = requireCurrentUserId();
    return queryService.listPendingForUser(userId);
  }

  @GetMapping(path = "/pending-changes/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Single pending change detail. 404 if not owned by the authenticated user.")
  public PendingChangeDto getById(@PathVariable("id") UUID id) {
    UUID userId = requireCurrentUserId();
    PendingChangeDto dto = queryService.getPendingChange(id).orElseThrow(() -> notFound(id));
    if (!dto.userId().equals(userId)) {
      throw notFound(id);
    }
    return dto;
  }

  @PostMapping(
      path = "/pending-changes/{id}/accept",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Accept a pending change; writes through RecipeWriteApi.")
  public PendingChangeDto accept(
      @PathVariable("id") UUID id, @Valid @RequestBody AcceptPendingChangeRequest body) {
    UUID userId = requireCurrentUserId();
    return adaptationService.acceptPendingChange(id, body, userId);
  }

  @PostMapping(
      path = "/pending-changes/{id}/reject",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Reject a pending change.")
  public PendingChangeDto reject(
      @PathVariable("id") UUID id, @Valid @RequestBody RejectPendingChangeRequest body) {
    UUID userId = requireCurrentUserId();
    return adaptationService.rejectPendingChange(id, body, userId);
  }

  @GetMapping(
      path = "/recipes/{recipeId}/pending-history",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated list of all pending changes ever proposed for a recipe.")
  public Page<PendingChangeListItemDto> pendingHistory(
      @PathVariable("recipeId") UUID recipeId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return pendingChangeRepository
        .findByRecipeIdOrderByCreatedAtDesc(recipeId, pageable)
        .map(pendingChangeMapper::toListItem);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  private static PendingChangeNotFoundException notFound(UUID id) {
    return new PendingChangeNotFoundException("pending change not found: " + id);
  }
}
