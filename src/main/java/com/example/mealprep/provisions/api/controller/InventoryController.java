package com.example.mealprep.provisions.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.provisions.api.dto.AdjustInventoryQuantityRequest;
import com.example.mealprep.provisions.api.dto.AdjustInventoryStatusRequest;
import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST seam for the provisions module's inventory aggregate. Authentication is enforced by the auth
 * module's deny-by-default chain; the {@link CurrentUserResolver} resolves the caller's {@code
 * userId} server-side — the controller never accepts a {@code userId} from path, query or body, so
 * user A cannot mutate items for user B.
 */
@RestController
@RequestMapping("/api/v1/provisions/inventory")
@Tag(name = "Provisions")
@Validated
public class InventoryController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private final ProvisionQueryService queryService;
  private final ProvisionUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public InventoryController(
      ProvisionQueryService queryService,
      ProvisionUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "List the calling user's pantry items (itemStatus defaults to ACTIVE; expiringWithinDays"
              + " narrows to items with expiryDate <= today + N).")
  public Page<InventoryItemDto> list(
      @RequestParam(required = false) StorageLocation storageLocation,
      @RequestParam(required = false) Boolean isStaple,
      @RequestParam(required = false) ItemLifecycleStatus itemStatus,
      @RequestParam(required = false) @Min(0) Integer expiringWithinDays,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    UUID userId = requireCurrentUserId();
    int safePage = Math.max(0, page);
    int safeSize = clampPageSize(size);
    Pageable pageable = PageRequest.of(safePage, safeSize);
    InventorySearchCriteria criteria =
        new InventorySearchCriteria(storageLocation, isStaple, itemStatus, expiringWithinDays);
    return queryService.listActiveInventory(userId, criteria, pageable);
  }

  @GetMapping(path = "/{itemId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get a single pantry item by id.")
  public InventoryItemDto getById(@PathVariable UUID itemId) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getInventoryItem(itemId, userId)
        .orElseThrow(() -> new InventoryItemNotFoundException(itemId));
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a new pantry item; userId is server-resolved from the session.")
  public ResponseEntity<InventoryItemDto> create(
      @Valid @RequestBody CreateInventoryItemRequest request) {
    UUID userId = requireCurrentUserId();
    InventoryItemDto created = updateService.createInventoryItem(userId, request, AuditActor.USER);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/provisions/inventory/" + created.id()))
        .body(created);
  }

  @PutMapping(
      path = "/{itemId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace a pantry item (full replacement; expectedVersion required).")
  public InventoryItemDto update(
      @PathVariable UUID itemId, @Valid @RequestBody UpdateInventoryItemRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.updateInventoryItem(itemId, userId, request);
  }

  @PatchMapping(
      path = "/{itemId}/quantity",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Adjust a pantry item's quantity (focused edit; expectedVersion required; quantity-tracked"
              + " items only).")
  public InventoryItemDto adjustQuantity(
      @PathVariable UUID itemId, @Valid @RequestBody AdjustInventoryQuantityRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.adjustQuantity(itemId, userId, request);
  }

  @PatchMapping(
      path = "/{itemId}/status",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Adjust a pantry item's staple status (focused edit; expectedVersion required;"
              + " status-tracked items only).")
  public InventoryItemDto adjustStatus(
      @PathVariable UUID itemId, @Valid @RequestBody AdjustInventoryStatusRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.adjustStatus(itemId, userId, request);
  }

  @DeleteMapping(path = "/{itemId}")
  @Operation(summary = "Soft-delete a pantry item (sets itemStatus=WASTED).")
  public ResponseEntity<Void> softDelete(@PathVariable UUID itemId) {
    UUID userId = requireCurrentUserId();
    updateService.softDeleteInventoryItem(itemId, userId);
    return ResponseEntity.noContent().build();
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
