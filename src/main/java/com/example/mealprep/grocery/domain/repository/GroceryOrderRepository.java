package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GroceryOrder}. Package-private — cross-module callers go
 * through {@code GroceryOrderService}. Verbatim from lld/grocery.md lines 501-511.
 * {@code @EntityGraph} on the hot-read load keeps order + lines to a single JOIN (no N+1).
 */
interface GroceryOrderRepository extends JpaRepository<GroceryOrder, UUID> {

  @EntityGraph(attributePaths = {"lines"})
  Optional<GroceryOrder> findWithLinesById(UUID id);

  Page<GroceryOrder> findAllByUserIdAndStatusNotInOrderByCreatedAtDesc(
      UUID userId, Collection<GroceryOrderStatus> excludedStatuses, Pageable p);

  Optional<GroceryOrder> findByProviderKeyAndProviderOrderId(
      String providerKey, String providerOrderId);

  @Query(
      """
      select o from GroceryOrder o where o.shoppingListId = :listId
        and o.status not in ('CANCELLED', 'RECONCILED', 'ARCHIVED')""")
  List<GroceryOrder> findActiveByShoppingListId(@Param("listId") UUID listId);
}
