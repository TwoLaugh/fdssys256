package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GroceryOrderLine}. Package-private. Standard-shape per
 * lld/grocery.md line 540.
 */
interface GroceryOrderLineRepository extends JpaRepository<GroceryOrderLine, UUID> {

  List<GroceryOrderLine> findAllByGroceryOrderId(UUID groceryOrderId);

  /**
   * The most-recently-created paid {@code providerProductId} for {@code (userId,
   * ingredientMappingKey)} across prior orders — the preferred-SKU hint for {@code
   * BasketDraftAssembler} (grocery-01e). Walks the parent order for the user scope.
   */
  @Query(
      """
      select l.providerProductId from GroceryOrderLine l
        where l.groceryOrder.userId = :userId
          and l.ingredientMappingKey = :key
          and l.providerProductId is not null
          and l.paidUnitPence is not null
        order by l.createdAt desc""")
  List<String> findLastPaidProviderProductIds(
      @Param("userId") UUID userId, @Param("key") String key, Pageable pageable);
}
