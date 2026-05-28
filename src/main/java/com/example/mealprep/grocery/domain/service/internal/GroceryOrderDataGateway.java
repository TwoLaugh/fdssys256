package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Internal data-access seam for the Tier-3 order lifecycle (grocery-01e). Exists because the
 * grocery repositories are package-private in {@code domain.repository} (enforced by {@code
 * GroceryBoundaryTest.reposArePackagePrivate}) and therefore not visible to {@code
 * domain.service.internal}. The package-private {@code GroceryOrderDataGatewayImpl} (co-located
 * WITH the repositories) implements this port and holds them; the Tier-3 service / assembler depend
 * only on this port. Mirrors {@code ShoppingListDataGateway} / {@code PriceDataGateway}.
 */
public interface GroceryOrderDataGateway {

  // ---- order reads / writes ----

  Optional<GroceryOrder> findOrderWithLinesById(UUID orderId);

  Optional<GroceryOrder> findOrderById(UUID orderId);

  List<GroceryOrder> findOrdersByIds(List<UUID> ids);

  Page<GroceryOrder> findMyOrders(UUID userId, Pageable pageable);

  List<GroceryOrder> findActiveOrdersByShoppingListId(UUID shoppingListId);

  GroceryOrder saveOrder(GroceryOrder order);

  GroceryOrder saveAndFlushOrder(GroceryOrder order);

  // ---- shopping list (the draft snapshot source) ----

  Optional<ShoppingList> findShoppingListWithLinesById(UUID shoppingListId);

  // ---- provider-state reads / writes ----

  Optional<GroceryProviderState> findProviderState(UUID userId, String providerKey);

  GroceryProviderState saveProviderState(GroceryProviderState state);

  /**
   * The most-recently-paid provider product id for {@code (userId, ingredientMappingKey)} across
   * prior orders — the preferred-SKU hint for {@code BasketDraftAssembler}. Empty when none.
   */
  Optional<String> findLastPaidProviderProductId(UUID userId, String ingredientMappingKey);

  // ---- immediate failure-state writes (bypass the persistence context; see OrderFailureRecorder)
  // ----

  /** Immediate bulk status+reason update for the failure-forward path (REQUIRES_NEW recorder). */
  void updateOrderStatusAndReason(
      UUID orderId, GroceryOrderStatus status, String reason, Instant now);

  /** Immediate provider-state failure-counter bump (REQUIRES_NEW recorder). */
  void bumpProviderFailure(UUID userId, String providerKey, String reason, Instant now);
}
