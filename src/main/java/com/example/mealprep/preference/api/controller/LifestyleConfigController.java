package com.example.mealprep.preference.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.dto.LifestyleConfigAuditEntryDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.exception.LifestyleConfigNotFoundException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the Tier-3 lifestyle config. Authentication is enforced by {@code
 * AuthSecurityConfig}'s deny-by-default chain; the {@link CurrentUserResolver} resolves the
 * caller's {@code userId} server-side — the controller never accepts a {@code userId} from a path
 * or query, so user A cannot read or update user B's config.
 *
 * <p>The {@code initialise} flow is intentionally NOT exposed on the REST surface here — the
 * onboarding wizard ticket is responsible for calling {@link
 * LifestyleConfigUpdateService#initialise} during the wizard's submit step. PUT will return 404
 * until {@code initialise} has been called.
 */
@RestController
@RequestMapping("/api/v1/preferences/lifestyle-config")
@Tag(name = "Preferences")
public class LifestyleConfigController {

  private final LifestyleConfigQueryService queryService;
  private final LifestyleConfigUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public LifestyleConfigController(
      LifestyleConfigQueryService queryService,
      LifestyleConfigUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Read the calling user's lifestyle config.")
  public LifestyleConfigDto get() {
    UUID userId = requireCurrentUserId();
    return queryService
        .getLifestyleConfig(userId)
        .orElseThrow(() -> new LifestyleConfigNotFoundException(userId));
  }

  @PutMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the calling user's lifestyle config.")
  public LifestyleConfigDto update(@Valid @RequestBody UpdateLifestyleConfigRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.update(userId, request, userId);
  }

  @PostMapping(path = "/mark-reviewed", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Acknowledge a behavioural-drift review nudge; resets lastReviewPromptAt.")
  public LifestyleConfigDto markReviewed() {
    UUID userId = requireCurrentUserId();
    return updateService.markReviewed(userId);
  }

  @GetMapping(path = "/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log of lifestyle-config changes; newest-first.")
  public Page<LifestyleConfigAuditEntryDto> auditLog(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) String section) {
    UUID userId = requireCurrentUserId();
    Pageable pageable = PageRequest.of(page, size);
    if (section == null || section.isBlank()) {
      return queryService.getAuditLog(userId, pageable);
    }
    return queryService.getAuditLogForSection(userId, section, pageable);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
