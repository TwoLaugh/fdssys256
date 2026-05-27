package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.ShoppingList;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ShoppingList}. Package-private — cross-module callers go
 * through {@code ShoppingListService}; the {@code GroceryBoundaryTest} ArchUnit rule is the
 * backstop.
 *
 * <p>DIVERGENCE (ticket 01a, locked): the LLD's {@code findByPlanIdAndPlanRevision} / {@code
 * ...OrderByPlanRevisionDesc} are renamed to {@code ...PlanGeneration} / {@code
 * ...OrderByPlanGenerationDesc} to track the {@code planGeneration} field rename.
 */
interface ShoppingListRepository extends JpaRepository<ShoppingList, UUID> {

  Optional<ShoppingList> findByPlanIdAndPlanGeneration(UUID planId, int generation);

  Optional<ShoppingList> findFirstByPlanIdAndSupersededAtIsNullOrderByPlanGenerationDesc(
      UUID planId);

  Page<ShoppingList> findAllByUserIdOrderByGeneratedAtDesc(UUID userId, Pageable p);

  @EntityGraph(attributePaths = {"lines"})
  Optional<ShoppingList> findWithLinesById(UUID id);
}
