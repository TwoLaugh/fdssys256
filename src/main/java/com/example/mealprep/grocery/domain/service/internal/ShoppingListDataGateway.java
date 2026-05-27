package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Internal data-access seam for the Tier-1 shopping-list capability (grocery-01b). Exists because
 * the grocery repositories are package-private in {@code domain.repository} (enforced by {@code
 * GroceryBoundaryTest.reposArePackagePrivate}) and therefore not visible to {@code
 * domain.service.internal}. The package-private {@code ShoppingListDataGatewayImpl} (co-located
 * WITH the repositories) implements this port and holds the repositories; the Tier-1 calculator /
 * service / listener depend only on this port. Mirrors the Tier-4 {@link PriceDataGateway} pattern.
 */
public interface ShoppingListDataGateway {

  // ---- shopping-list reads ----

  Optional<ShoppingList> findByPlanIdAndPlanGeneration(UUID planId, int generation);

  Optional<ShoppingList> findActiveByPlanId(UUID planId);

  List<ShoppingList> findActiveByUserId(UUID userId);

  Page<ShoppingList> findHistoryByUserId(UUID userId, Pageable pageable);

  Optional<ShoppingList> findWithLinesById(UUID id);

  // ---- shopping-list write ----

  ShoppingList saveAndFlush(ShoppingList list);

  // ---- pack-size heuristic reads ----

  List<PackSizeHeuristic> findPacksByKey(String ingredientMappingKey);

  List<PackSizeHeuristic> findPacksByCategory(String category);
}
