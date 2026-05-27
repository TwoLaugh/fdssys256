package com.example.mealprep.grocery.domain.service;

import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtResultDto;
import java.util.List;
import java.util.UUID;

/**
 * Tier 2 — manual fulfilment (mark-bought). Public service contract (declarations only in 01a;
 * implemented in grocery-01d). Per lld/grocery.md lines 577-585.
 */
public interface ManualFulfilmentService {

  MarkBoughtResultDto markBought(UUID userId, MarkBoughtRequest request);

  List<MarkBoughtResultDto> bulkMarkBought(UUID userId, BulkMarkBoughtRequest request);

  /**
   * Used when the user changes their mind about an entry made via Tier 2. Reverses the inventory
   * add and the price observation.
   */
  void undoMarkBought(UUID shoppingListLineId, UUID actorUserId);
}
