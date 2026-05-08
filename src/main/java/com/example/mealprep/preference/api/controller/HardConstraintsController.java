package com.example.mealprep.preference.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the hard-constraints aggregate. Authentication is enforced by {@code
 * AuthSecurityConfig}'s deny-by-default chain; the {@link CurrentUserResolver} resolves the
 * caller's {@code userId} server-side — the controller never accepts a {@code userId} from a path
 * or query param, so user A cannot read or update user B's aggregate.
 */
@RestController
@RequestMapping("/api/v1/preferences/hard-constraints")
@Tag(name = "Preferences")
public class HardConstraintsController {

  private final PreferenceQueryService queryService;
  private final PreferenceUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HardConstraintsController(
      PreferenceQueryService queryService,
      PreferenceUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read the calling user's hard-constraints aggregate.")
  public HardConstraintsDto get() {
    UUID userId = requireCurrentUserId();
    return queryService
        .getHardConstraints(userId)
        .orElseThrow(() -> new HardConstraintsNotFoundException(userId));
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the calling user's hard-constraints aggregate.")
  public HardConstraintsDto update(@Valid @RequestBody UpdateHardConstraintsRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.updateHardConstraints(userId, request, userId);
  }

  @GetMapping(path = "/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log of hard-constraints changes; newest-first.")
  public Page<HardConstraintsAuditEntryDto> auditLog(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getHardConstraintsAuditLog(userId, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
