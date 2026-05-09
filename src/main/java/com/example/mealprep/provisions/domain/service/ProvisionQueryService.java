package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
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
}
