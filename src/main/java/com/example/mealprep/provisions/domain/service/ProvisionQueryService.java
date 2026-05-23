package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.api.dto.WasteSummaryDto;
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

  /**
   * Distinct user-ids that own at least one {@code ACTIVE} inventory row. Read-only cross-module
   * helper for the notification/01b expiry + staple scanners, which iterate users then load each
   * user's candidate rows. No HTTP exposure.
   */
  List<UUID> getUserIdsWithActiveInventory();

  /**
   * {@code ACTIVE} inventory items for {@code userId} whose non-null {@code expiryDate} is on or
   * before {@code maxExpiryDate}, soonest-expiring first. Read-only cross-module helper for the
   * notification/01b {@code ExpiryWarningScanner}; the scanner passes {@code today + the widest
   * per-location threshold} and applies the precise fridge/freezer/pantry cut-off in code.
   */
  List<InventoryItemDto> getExpiringInventory(UUID userId, LocalDate maxExpiryDate);

  /**
   * {@code ACTIVE} FREEZER items for {@code userId} that carry both a defrost lead-time and an
   * {@code expiryDate}. Read-only cross-module helper for the notification/01b {@code
   * DefrostReminderScanner}; the lead-time + use-by anchor live in provisions per {@code
   * design/provision-model.md}.
   */
  List<InventoryItemDto> getDefrostCandidates(UUID userId);

  /**
   * {@code ACTIVE} staple items for {@code userId} at/below restock level (status {@code LOW} or
   * {@code OUT}). Read-only cross-module helper for the notification/01b {@code
   * StapleReplenishmentScanner}.
   */
  List<InventoryItemDto> getStaplesNeedingReplenishment(UUID userId);

  /**
   * ACTIVE inventory rows for {@code (userId, ingredientMappingKey)}, oldest-expiry first (NULLS
   * LAST). Read-only cross-module helper for the feedback module's MARK_DEPLETED bridge ("I'm out
   * of soy sauce"). Empty list when the user has no active rows for that key. No HTTP exposure.
   */
  List<InventoryItemDto> getActiveInventoryByMappingKey(UUID userId, String ingredientMappingKey);

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

  /**
   * Paginated waste-log history for {@code userId} within {@code [from, to]}. Sorted by {@code
   * occurredOn DESC}. Caller (the controller) is responsible for applying the default 90-day window
   * if either bound is omitted.
   */
  Page<WasteEntryDto> getWasteEntries(UUID userId, LocalDate from, LocalDate to, Pageable p);

  /**
   * Aggregate view of waste over {@code [from, to]} for {@code userId}: total cost-estimate, total
   * entry count, count grouped by {@link com.example.mealprep.provisions.api.dto.WasteReason}, and
   * the top-10 most-frequently-wasted items (sorted by {@code entryCount DESC}, tie-break {@code
   * totalCost DESC}).
   */
  WasteSummaryDto getWasteSummary(UUID userId, LocalDate from, LocalDate to);

  /**
   * Cross-module helper returning every waste entry for {@code userId} within {@code [from, to]}.
   * Designed for a future analytics aggregator (deferred). Capped at 1000 rows server-side; when
   * the cap is exceeded the first 1000 rows are returned and a {@code WARN} is logged ({@code
   * waste-window-cap-exceeded}). Callers expecting unbounded results should switch to the paginated
   * {@link #getWasteEntries} path.
   */
  List<WasteEntryDto> getWasteForUserInWindow(UUID userId, LocalDate from, LocalDate to);
}
