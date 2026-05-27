package com.example.mealprep.grocery.domain.service;

import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.api.dto.ShoppingListExportDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tier 1 — shopping list. Public service contract (declarations only in 01a; implemented in
 * grocery-01b). Per lld/grocery.md lines 556-569.
 */
public interface ShoppingListService {

  Optional<ShoppingListDto> getCurrentByPlanId(UUID planId);

  Optional<ShoppingListDto> getById(UUID shoppingListId);

  List<ShoppingListDto> getByIds(List<UUID> ids);

  Page<ShoppingListDto> getHistory(UUID userId, Pageable pageable);

  /**
   * Recalculate from a plan + provisions snapshot. Idempotent on {@code (planId, planGeneration)}:
   * re-running with the same generation returns the existing row; a new generation creates a new
   * shopping list and supersedes the previous one.
   */
  ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request);

  /** Render in the requested format. Pure transformation — no side effects. */
  ShoppingListExportDto export(UUID shoppingListId, ExportFormat format);
}
