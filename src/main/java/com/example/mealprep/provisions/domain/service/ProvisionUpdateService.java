package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.UpsertEquipmentRequest;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import java.util.UUID;

/**
 * Write API for the provisions module — partial in 01a (inventory create/update only). The
 * cook-event and grocery-import flows, the {@code mark-spoiled}/{@code mark-exhausted} lifecycle
 * endpoints, and the quantity-adjust patch land in 01b/01g/01h.
 */
public interface ProvisionUpdateService {

  /**
   * Create a new inventory item owned by {@code userId}. {@code actor} is recorded on the audit
   * row; HTTP create paths pass {@link AuditActor#USER}. Throws {@code
   * InvalidInventoryQuantityException} when the tracking-mode invariant or non-negative quantity
   * rule is violated.
   */
  InventoryItemDto createInventoryItem(
      UUID userId, CreateInventoryItemRequest request, AuditActor actor);

  /**
   * Replace an existing inventory item. The caller must own the item (else 404) and supply the
   * latest {@code expectedVersion} (else 409). One audit row is written per genuinely changed field
   * with {@link AuditActor#USER}; no-op PUTs write nothing and do not bump {@code version}.
   */
  InventoryItemDto updateInventoryItem(
      UUID itemId, UUID requestingUserId, UpdateInventoryItemRequest request);

  /**
   * Create or update an equipment row keyed by {@code (userId, name)}. {@code expectedVersion} is
   * required for updates and ignored on insert. Throws {@code OptimisticLockingFailureException} on
   * stale version (mapped to 409 by {@code GlobalExceptionHandler}). The result's {@link
   * UpsertResult#created()} flag distinguishes 201-create from 200-update for the controller.
   */
  UpsertResult<EquipmentDto> upsertEquipment(
      UUID userId, String name, UpsertEquipmentRequest request);

  /** Wrapper carrying both the saved DTO and a flag for whether the call inserted or updated. */
  record UpsertResult<T>(T value, boolean created) {}

  /** Delete the equipment row for {@code (userId, name)}. Throws 404 if not present. */
  void deleteEquipment(UUID userId, String name);

  /**
   * Mark an inventory item as {@code SPOILED}. Idempotent — already-spoiled rows are returned
   * unchanged with no audit row and no event. Throws 404 when the caller does not own the item.
   */
  InventoryItemDto markSpoiled(UUID itemId, UUID actorUserId);

  /**
   * Mark an inventory item as {@code EXHAUSTED}. Idempotent — already-exhausted rows are returned
   * unchanged with no audit row and no event. Throws 404 when the caller does not own the item.
   */
  InventoryItemDto markExhausted(UUID itemId, UUID actorUserId);

  /**
   * Soft-delete an inventory item by setting {@code itemStatus = WASTED}. Idempotent on already-
   * wasted rows. No event emitted (the row is going away; listeners shouldn't react). Throws 404
   * when the caller does not own the item.
   */
  void softDeleteInventoryItem(UUID itemId, UUID actorUserId);
}
