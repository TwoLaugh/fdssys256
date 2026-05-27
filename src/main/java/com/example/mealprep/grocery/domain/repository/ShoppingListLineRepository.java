package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ShoppingListLine}. Package-private — cross-module callers go
 * through {@code ShoppingListService} / {@code ManualFulfilmentService}. Standard-shape per
 * lld/grocery.md line 539, with the {@code findAllByIdIn} batch sibling per the style guide.
 */
interface ShoppingListLineRepository extends JpaRepository<ShoppingListLine, UUID> {

  List<ShoppingListLine> findAllByShoppingListId(UUID shoppingListId);

  List<ShoppingListLine> findAllByIdIn(Collection<UUID> ids);

  /** Fetch a line with its parent {@code ShoppingList} eagerly joined (Tier 2 mark-bought). */
  @EntityGraph(attributePaths = {"shoppingList"})
  Optional<ShoppingListLine> findWithListById(UUID id);

  /** Batch fetch lines with parents eagerly joined (Tier 2 bulk mark-bought). */
  @EntityGraph(attributePaths = {"shoppingList"})
  List<ShoppingListLine> findWithListByIdIn(Collection<UUID> ids);
}
