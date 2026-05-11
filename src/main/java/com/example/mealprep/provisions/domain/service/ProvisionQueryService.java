package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the provisions module — partial in 01a (inventory only). Equipment, budget,
 * supplier-products, waste, and the planner-bundle reads land in 01b/01c/01d/01e/01f.
 */
public interface ProvisionQueryService {

  /**
   * Look up a single inventory item by id, scoped to the requesting user. Returns empty if the row
   * is missing OR owned by another user — controllers must surface a 404 in either case so
   * existence is not leaked.
   */
  Optional<InventoryItemDto> getInventoryItem(UUID itemId, UUID requestingUserId);

  /**
   * Page of {@code ACTIVE} inventory items belonging to {@code userId}, optionally narrowed by
   * {@link InventorySearchCriteria}. Soft-deleted (exhausted/spoiled/wasted) rows are not returned.
   */
  Page<InventoryItemDto> listActiveInventory(
      UUID userId, InventorySearchCriteria criteria, Pageable pageable);

  /** Equipment rows for {@code userId}, sorted by {@code name} ascending. */
  List<EquipmentDto> getEquipment(UUID userId);

  /** Equipment rows for {@code userId} with {@code available = true}, sorted by {@code name}. */
  List<EquipmentDto> getAvailableEquipment(UUID userId);

  /**
   * Newest-first paginated audit log for an inventory item. The caller must own the item, else a
   * {@code InventoryItemNotFoundException} is thrown (404 — does not leak existence).
   */
  Page<InventoryAuditEntryDto> getInventoryAuditLog(
      UUID itemId, UUID requestingUserId, Pageable pageable);

  /**
   * Return the budget aggregate for {@code userId}, or empty if no row exists yet. {@code
   * spendTracking} on the returned DTO is always {@code null} in v1 (provisions-01c) — populated by
   * 01f/01h.
   */
  Optional<BudgetDto> getBudget(UUID userId);

  /**
   * Batch budget read for cross-module callers (e.g. 01f's planner-bundle aggregator). Single
   * round-trip; returns one {@link BudgetDto} per persisted row (rows missing for a given userId
   * are simply absent from the response — callers must handle the partial). {@code spendTracking}
   * on every entry is always {@code null} in v1.
   */
  List<BudgetDto> getBudgetsByUserIds(List<UUID> userIds);

  /**
   * Return the single cheapest supplier-product matching {@code key} on the {@code
   * ingredientMappingKey} column — multiple rows can share the same key (one mapping → many
   * supplier SKUs). Tie-break order: {@code price_per_unit ASC}, then {@code last_checked DESC}
   * (freshest first). Empty when no rows match. Internal cross-module helper — no HTTP exposure.
   */
  Optional<SupplierProductDto> getSupplierProductByMappingKey(String key);

  /**
   * Batch variant of {@link #getSupplierProductByMappingKey(String)} — one round-trip via {@code IN
   * (...)}. Returns a map keyed by {@code ingredientMappingKey} with the cheapest entry per key;
   * missing keys are silently absent. Designed for 01f's planner-bundle cost-estimation path. Empty
   * input → empty map; no repository call.
   */
  Map<String, SupplierProductDto> getSupplierProductsByMappingKeys(Collection<String> keys);

  /**
   * Paginated stale-supplier-product listing — rows where {@code last_checked < cutoff}. Sorted by
   * {@code last_checked ASC} (oldest first — the staleness UI surfaces the most stale entries
   * first). Internal helper; the admin-staleness REST surface is deferred to 01j.
   */
  Page<SupplierProductDto> getStaleSupplierProducts(LocalDate cutoff, Pageable p);

  /**
   * Paginated search backing {@code GET /supplier-products?mappingKey=&supplier=}. Both filters are
   * optional — when both are null every row matches. Sort comes from the caller's {@link Pageable};
   * the controller pins {@code last_checked DESC} by default.
   */
  Page<SupplierProductDto> searchSupplierProducts(String mappingKey, String supplier, Pageable p);
}
