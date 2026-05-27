package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link PackSizeHeuristic} reference data. Package-private.
 * Standard-shape per lld/grocery.md line 543 — lookup by mapping key (then category fallback),
 * ranked smallest-first.
 */
interface PackSizeHeuristicRepository extends JpaRepository<PackSizeHeuristic, UUID> {

  List<PackSizeHeuristic> findAllByIngredientMappingKeyOrderByRankAsc(String ingredientMappingKey);

  List<PackSizeHeuristic> findAllByCategoryOrderByRankAsc(String category);
}
