package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
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
}
