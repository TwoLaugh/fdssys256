package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.CookEventCommand;
import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.InventoryDeductionResultDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.MealConsumptionCommand;
import com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand;
import com.example.mealprep.provisions.api.dto.SubstitutionRecordDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.UpdateBudgetRequest;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.UpsertEquipmentRequest;
import com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest;
import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import java.util.Optional;
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

  /**
   * PUT-as-upsert for the budget aggregate (provisions-01c collapses the LLD's separate {@code
   * initialiseBudget} / {@code updateBudget} entry-points into one — see the {@code @Deprecated}
   * default forwarders below). On insert {@code expectedVersion} is ignored; on update a stale
   * value triggers {@code OptimisticLockingFailureException} (mapped to 409). A re-PUT with no
   * field change is a no-op — no version bump and no event. Currency cannot be changed on the
   * update path: throws {@code BudgetCurrencyChangeException} (mapped to 422).
   */
  BudgetDto upsertBudget(UUID userId, UpdateBudgetRequest request);

  /**
   * @deprecated 01c collapses to {@link #upsertBudget(UUID, UpdateBudgetRequest)}. Kept as a
   *     default forwarder so any caller that follows the LLD's two-method signature verbatim keeps
   *     compiling.
   */
  @Deprecated
  default BudgetDto initialiseBudget(UUID userId, UpdateBudgetRequest request) {
    return upsertBudget(userId, request);
  }

  /**
   * @deprecated 01c collapses to {@link #upsertBudget(UUID, UpdateBudgetRequest)}. Kept as a
   *     default forwarder so any caller that follows the LLD's two-method signature verbatim keeps
   *     compiling.
   */
  @Deprecated
  default BudgetDto updateBudget(UUID userId, UpdateBudgetRequest request) {
    return upsertBudget(userId, request);
  }

  /**
   * Upsert a supplier-product row keyed by {@code (supplier, productId)}. The {@code lastChecked}
   * field is always refreshed (the freshness clock is the whole point of a no-op upsert); {@code
   * substitutionHistory} is preserved on update. The endpoint deliberately does NOT enforce an
   * {@code expectedVersion} — supplier-product pricing churn is high-frequency and forcing
   * optimistic-lock collisions would surface user-facing 409s on concurrent grocery imports. The
   * {@link UpsertResult#created()} flag distinguishes 201-create from 200-update for the
   * controller.
   */
  UpsertResult<SupplierProductDto> upsertSupplierProduct(UpsertSupplierProductRequest request);

  /**
   * Append one substitution record to a supplier product's JSONB {@code substitution_history} list.
   * Race protection is via {@code @Version} (concurrent appenders collide → loser gets 409). The
   * {@code actorUserId} is the {@code CurrentUserResolver}-resolved caller; the supplier product
   * itself is global reference data with no per-user ownership — the userId is only an audit trail
   * for who recorded the decision.
   *
   * <p>LLD divergence: the LLD's signature is {@code (UUID, SubstitutionRecordDto, boolean)}; 01d
   * adds {@code actorUserId} and {@code expectedVersion} for the {@link
   * com.example.mealprep.provisions.event.SubstitutionAcceptedEvent} payload + concurrency guard.
   */
  SupplierProductDto recordSubstitution(
      UUID supplierProductId,
      SubstitutionRecordDto record,
      boolean userAccepted,
      UUID actorUserId,
      long expectedVersion);

  /**
   * Append a waste-log row and, when the request carries an {@code inventoryItemId} the caller
   * owns, deduct the wasted quantity from the linked inventory row (floor at zero), write an {@code
   * InventoryAuditLog} entry, mark the inventory row {@code WASTED} when exhausted, and publish the
   * appropriate {@code AFTER_COMMIT} event ({@code ItemQuantityAdjustedEvent} for QUANTITY-tracked
   * items, {@code ItemSpoiledEvent} for STATUS-tracked items).
   *
   * <p>Free-form waste (request without {@code inventoryItemId}) persists only the waste row — no
   * inventory mutation, no audit row, no inventory event. Throws {@code
   * InventoryItemNotFoundException} (404) when the linked item is missing / not owned and {@code
   * WasteExceedsInventoryException} (422) when quantity exceeds remaining stock on a
   * QUANTITY-tracked item.
   */
  WasteEntryDto logWaste(UUID userId, LogWasteRequest request);

  /**
   * Apply a cook event for {@code userId} per LLD §Flow 1. Walks {@code command.ingredientsUsed},
   * deducts from active inventory FIFO-by-expiry, writes audit rows, coalesces a single {@code
   * ItemQuantityAdjustedEvent(source=COOK_EVENT)} at AFTER_COMMIT, and returns the deduction
   * outcome (updated items + exhausted IDs + underflows). Idempotent by {@code (mealSlotId,
   * dedupeKey)}: a duplicate replay returns an empty result and writes nothing.
   *
   * <p>Throws {@code InventoryUnderflowException} (422) when {@code command.strict == true} and the
   * pantry can't cover. Throws {@code BatchCookNotSupportedException} (422) when {@code
   * command.isBatchCook == true} (v1 stop-gap; full split lands in 01j).
   */
  InventoryDeductionResultDto applyCookEvent(UUID userId, CookEventCommand command);

  /**
   * Decrement a specific inventory row by {@code command.portions} per LLD §Flow 2 line 424. Floor
   * at zero. Throws 404 when the item is missing or not owned by {@code userId}. Publishes {@code
   * ItemQuantityAdjustedEvent(source=MEAL_CONSUMPTION)} at AFTER_COMMIT.
   */
  InventoryDeductionResultDto applyMealConsumption(UUID userId, MealConsumptionCommand command);

  /**
   * Nutrition Logger path per LLD §Flow 3 line 425. Finds active rows for {@code (userId,
   * ingredientMappingKey)}; returns {@code Optional.empty()} when none match (per LLD line 646 —
   * "unrelated logged item, no error"). When {@code userConfirmedDeduction == true}, decrements the
   * oldest-expiry row by {@code command.quantity}; otherwise returns the matched row unchanged.
   * Publishes {@code ItemQuantityAdjustedEvent(source=STANDALONE_LOG)} at AFTER_COMMIT on the
   * mutation path.
   */
  Optional<InventoryItemDto> applyStandaloneConsumption(
      UUID userId, StandaloneConsumptionCommand command);

  /**
   * Apply a grocery-order import for {@code userId} per LLD §Flow 2. Idempotent on {@code (userId,
   * supplier, orderRef)} via the {@code provision_grocery_import_log} table; a duplicate replay
   * throws {@code DuplicateGroceryImportException} (409). Per-line: upserts the supplier-product
   * cache, infers storage location + expiry, then either merges into an existing inventory row
   * (same {@code userId + ingredientMappingKey + storageLocation + expiryDate}) or creates a new
   * one. Substitutions append to the matching supplier product's {@code substitutionHistory} JSONB.
   * Audit rows are written with {@code actor = GROCERY_IMPORT}; a single coalesced {@code
   * ItemAddedFromGroceryEvent} per import is published at {@code AFTER_COMMIT}.
   *
   * <p>The whole import is atomic — any per-line failure rolls the transaction back (LLD line 640
   * "partial failure"; the grocery module is responsible for "of 5 ordered, 3 arrived").
   */
  GroceryImportResultDto applyGroceryOrder(UUID userId, GroceryOrderImportCommand command);
}
