package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ShoppingListLine}. Package-private — cross-module callers go
 * through {@code ShoppingListService} / {@code ManualFulfilmentService}. Standard-shape per
 * lld/grocery.md line 539, with the {@code findAllByIdIn} batch sibling per the style guide.
 */
interface ShoppingListLineRepository extends JpaRepository<ShoppingListLine, UUID> {

  List<ShoppingListLine> findAllByShoppingListId(UUID shoppingListId);

  List<ShoppingListLine> findAllByIdIn(Collection<UUID> ids);
}
