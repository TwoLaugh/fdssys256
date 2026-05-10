package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin lifecycle endpoints over the inventory aggregate (deferred from 01a). Soft-delete itself
 * lives on {@link InventoryController} (DELETE /inventory/{itemId}); this class hosts mark-spoiled,
 * mark-exhausted, and the audit-log GET.
 */
@RestController
@RequestMapping("/api/v1/provisions/inventory")
@Tag(name = "Provisions")
public class InventoryAdminController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public InventoryAdminController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(path = "/{itemId}/mark-spoiled", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark an inventory item as spoiled (idempotent).")
  public InventoryItemDto markSpoiled(@PathVariable UUID itemId) {
    UUID userId = requireCurrentUserId();
    return updateService.markSpoiled(itemId, userId);
  }

  @PostMapping(path = "/{itemId}/mark-exhausted", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Mark an inventory item as exhausted (idempotent).")
  public InventoryItemDto markExhausted(@PathVariable UUID itemId) {
    UUID userId = requireCurrentUserId();
    return updateService.markExhausted(itemId, userId);
  }

  @GetMapping(path = "/{itemId}/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated audit log for an inventory item (newest first).")
  public Page<InventoryAuditEntryDto> getAuditLog(
      @PathVariable UUID itemId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    UUID userId = requireCurrentUserId();
    int safePage = Math.max(0, page);
    int safeSize = clampPageSize(size);
    Pageable pageable = PageRequest.of(safePage, safeSize);
    return queryService.getInventoryAuditLog(itemId, userId, pageable);
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
