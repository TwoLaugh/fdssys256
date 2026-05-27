package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GroceryOrderLine}. Package-private. Standard-shape per
 * lld/grocery.md line 540.
 */
interface GroceryOrderLineRepository extends JpaRepository<GroceryOrderLine, UUID> {

  List<GroceryOrderLine> findAllByGroceryOrderId(UUID groceryOrderId);
}
