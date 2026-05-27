package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.GroceryOrderLine;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tier-2 inventory-write bridge (grocery-01d): assembles a {@link GroceryOrderImportCommand} from
 * one-or-more marked-bought {@link ShoppingListLine}s and drives the canonical, idempotent
 * provisions inventory-add path ({@link ProvisionUpdateService#applyGroceryOrder}). Package-private
 * internal plumbing — the controller never touches provisions directly.
 *
 * <p><b>Idempotency.</b> {@code orderRef} is the shopping-list-line-id (single mark) or the
 * shopping-list-id (bulk), so the provisions log key {@code (userId, source, sourceRef)} prevents
 * double-adds on retry: a re-apply throws {@code DuplicateGroceryImportException} (409) rather than
 * adding inventory twice.
 *
 * <p><b>Pence ↔ pounds boundary.</b> Grocery stores integer pence; provisions' {@code
 * GroceryOrderLine.pricePaid} is {@code BigDecimal} POUNDS. {@link #pricePounds(Integer)} converts
 * at the boundary — {@code pence / 100} at scale 2 (HALF_UP); since pence is already the minor unit
 * this is exact (e.g. 450 → 4.50). A null price → null pounds (no price captured).
 */
@Component
class MarkBoughtInventoryBridge {

  /** Default supplier when the user did not name a store (mark-bought without a store). */
  static final String DEFAULT_SUPPLIER = "manual";

  /** Product-id prefix for a synthetic manual SKU (no real provider product behind it). */
  static final String MANUAL_PRODUCT_PREFIX = "manual:";

  private final ProvisionUpdateService provisionUpdateService;
  private final Clock clock;

  MarkBoughtInventoryBridge(ProvisionUpdateService provisionUpdateService, Clock clock) {
    this.provisionUpdateService = provisionUpdateService;
    this.clock = clock;
  }

  /** One marked-bought line + its bought quantity/unit/price (pence) for a single mark-bought. */
  record BoughtLine(
      ShoppingListLine line,
      BigDecimal boughtQuantity,
      String boughtUnit,
      Integer boughtPricePence) {}

  /**
   * Apply a single-line mark-bought: {@code orderRef = line id} (the idempotency key). Returns the
   * provisions result so the caller can surface the inventory-item id.
   */
  GroceryImportResultDto applySingle(UUID userId, String store, BoughtLine bought, UUID traceId) {
    GroceryOrderImportCommand command =
        new GroceryOrderImportCommand(
            supplier(store),
            bought.line().getId().toString(), // orderRef == line id → idempotency key
            LocalDate.now(clock),
            List.of(toOrderLine(bought)),
            List.of(),
            traceId);
    return provisionUpdateService.applyGroceryOrder(userId, command);
  }

  /**
   * Apply a bulk mark-bought as ONE {@code applyGroceryOrder} call (one provisions event). The
   * {@code orderRef} is a deterministic key over the batch's line-ids ({@link #bulkOrderRef}), so a
   * retry of the SAME batch is idempotent (no double-add) while a DIFFERENT batch on the same list
   * does not falsely collide. All lines must belong to the same shopping list (caller's guarantee).
   */
  GroceryImportResultDto applyBulk(
      UUID userId, String store, List<BoughtLine> boughtLines, UUID traceId) {
    List<GroceryOrderLine> orderLines = new ArrayList<>(boughtLines.size());
    List<UUID> lineIds = new ArrayList<>(boughtLines.size());
    for (BoughtLine bought : boughtLines) {
      orderLines.add(toOrderLine(bought));
      lineIds.add(bought.line().getId());
    }
    GroceryOrderImportCommand command =
        new GroceryOrderImportCommand(
            supplier(store),
            bulkOrderRef(lineIds), // deterministic per-batch idempotency key
            LocalDate.now(clock),
            orderLines,
            List.of(),
            traceId);
    return provisionUpdateService.applyGroceryOrder(userId, command);
  }

  /** Deterministic per-batch orderRef: a UUID name-derived from the sorted line-ids. */
  static String bulkOrderRef(List<UUID> lineIds) {
    List<String> sorted = new ArrayList<>(lineIds.size());
    for (UUID id : lineIds) {
      sorted.add(id.toString());
    }
    sorted.sort(null);
    return "bulk:"
        + UUID.nameUUIDFromBytes(String.join(",", sorted).getBytes(StandardCharsets.UTF_8));
  }

  /** The inventory-item id of the (single) row added or merged by an import, if any. */
  static Optional<UUID> firstInventoryItemId(GroceryImportResultDto result) {
    if (result == null) {
      return Optional.empty();
    }
    for (InventoryItemDto item : result.addedItems()) {
      return Optional.of(item.id());
    }
    for (InventoryItemDto item : result.mergedItems()) {
      return Optional.of(item.id());
    }
    return Optional.empty();
  }

  private GroceryOrderLine toOrderLine(BoughtLine bought) {
    ShoppingListLine line = bought.line();
    return new GroceryOrderLine(
        MANUAL_PRODUCT_PREFIX + line.getIngredientMappingKey(),
        line.getDisplayName(),
        line.getIngredientMappingKey(), // already normalised at the write boundary (core-03)
        bought.boughtQuantity(),
        bought.boughtUnit(),
        pricePounds(bought.boughtPricePence()),
        null, // category — not tracked on a shopping-list line; provisions defaults it
        line.getSuggestedPackSizeG());
  }

  private static String supplier(String store) {
    return store != null && !store.isBlank() ? store : DEFAULT_SUPPLIER;
  }

  /**
   * Convert integer pence → {@code BigDecimal} pounds (scale 2). Null pence → null pounds. Pence is
   * already the minor unit so the divide is exact; HALF_UP is a no-op guard at scale 2.
   */
  static BigDecimal pricePounds(Integer pence) {
    if (pence == null) {
      return null;
    }
    return BigDecimal.valueOf(pence).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }
}
