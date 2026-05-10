package com.example.mealprep.household.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for household settings + audit-log + slot-configuration. Authentication is enforced by
 * the auth module's deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's
 * {@code userId} server-side — the controller never accepts a {@code userId} from path or query, so
 * user A cannot mutate or read user B's settings.
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}")
@Tag(name = "Households")
public class HouseholdSettingsController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private final HouseholdQueryService queryService;
  private final HouseholdUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public HouseholdSettingsController(
      HouseholdQueryService queryService,
      HouseholdUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(path = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Return a household's settings document.")
  public HouseholdSettingsDto getSettings(@PathVariable UUID householdId) {
    UUID callerUserId = requireCurrentUserId();
    return queryService
        .getSettings(householdId, callerUserId)
        .orElseThrow(() -> new HouseholdSettingsNotFoundException(householdId));
  }

  @PutMapping(
      path = "/settings",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace a household's settings document (primary-only).")
  public HouseholdSettingsDto updateSettings(
      @PathVariable UUID householdId, @Valid @RequestBody UpdateHouseholdSettingsRequest request) {
    UUID callerUserId = requireCurrentUserId();
    return updateService.updateSettings(householdId, callerUserId, request);
  }

  @GetMapping(path = "/settings/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit-log of changes to a household's settings.")
  public Page<HouseholdSettingsAuditEntryDto> getSettingsAuditLog(
      @PathVariable UUID householdId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    UUID callerUserId = requireCurrentUserId();
    int safePage = Math.max(0, page);
    int safeSize = clampPageSize(size);
    Pageable pageable = PageRequest.of(safePage, safeSize);
    return queryService.getSettingsAuditLog(householdId, callerUserId, pageable);
  }

  @GetMapping(path = "/slot-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Return resolved slot configuration (defaults + custom slots + eaters).")
  public SlotConfigurationDto getSlotConfiguration(@PathVariable UUID householdId) {
    UUID callerUserId = requireCurrentUserId();
    return queryService.getSlotConfiguration(householdId, callerUserId);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }

  private static int clampPageSize(int size) {
    if (size < 1) return DEFAULT_PAGE_SIZE;
    return Math.min(size, MAX_PAGE_SIZE);
  }
}
