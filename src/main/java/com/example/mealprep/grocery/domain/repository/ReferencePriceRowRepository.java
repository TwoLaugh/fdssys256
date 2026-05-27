package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.ReferencePriceRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ReferencePriceRow} (01c Tier-4 reference prices).
 * Package-private — cross-module callers route through the grocery service interfaces / the {@code
 * ReferencePriceSource} SPI, never the repository directly (enforced by {@code
 * GroceryBoundaryTest}).
 *
 * <p>{@code findByIngredientMappingKey} backs the single-key SPI read; {@code
 * findByIngredientMappingKeyIn} is the batch sibling the {@code getAggregatesByKeys} ≤5-SQL target
 * relies on (ONE reference query for all keys).
 */
interface ReferencePriceRowRepository extends JpaRepository<ReferencePriceRow, UUID> {

  Optional<ReferencePriceRow> findByIngredientMappingKey(String ingredientMappingKey);

  List<ReferencePriceRow> findByIngredientMappingKeyIn(Collection<String> keys);
}
