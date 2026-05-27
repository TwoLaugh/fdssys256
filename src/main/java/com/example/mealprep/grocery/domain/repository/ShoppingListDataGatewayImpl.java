package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.service.internal.ShoppingListDataGateway;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Package-private adapter implementing the public {@link ShoppingListDataGateway} port
 * (grocery-01b). Co-located with the package-private grocery repositories so it can hold them (they
 * are not visible outside this package — {@code GroceryBoundaryTest.reposArePackagePrivate}). The
 * Tier-1 calculator / service / listener in {@code domain.service.internal} inject the port, never
 * the repositories. Mirrors {@code PriceDataGatewayImpl}.
 */
@Component
class ShoppingListDataGatewayImpl implements ShoppingListDataGateway {

  private final ShoppingListRepository shoppingListRepository;
  private final PackSizeHeuristicRepository packSizeHeuristicRepository;

  ShoppingListDataGatewayImpl(
      ShoppingListRepository shoppingListRepository,
      PackSizeHeuristicRepository packSizeHeuristicRepository) {
    this.shoppingListRepository = shoppingListRepository;
    this.packSizeHeuristicRepository = packSizeHeuristicRepository;
  }

  @Override
  public Optional<ShoppingList> findByPlanIdAndPlanGeneration(UUID planId, int generation) {
    return shoppingListRepository.findByPlanIdAndPlanGeneration(planId, generation);
  }

  @Override
  public Optional<ShoppingList> findActiveByPlanId(UUID planId) {
    return shoppingListRepository.findFirstByPlanIdAndSupersededAtIsNullOrderByPlanGenerationDesc(
        planId);
  }

  @Override
  public List<ShoppingList> findActiveByUserId(UUID userId) {
    return shoppingListRepository.findAllByUserIdAndSupersededAtIsNull(userId);
  }

  @Override
  public Page<ShoppingList> findHistoryByUserId(UUID userId, Pageable pageable) {
    return shoppingListRepository.findAllByUserIdOrderByGeneratedAtDesc(userId, pageable);
  }

  @Override
  public Optional<ShoppingList> findWithLinesById(UUID id) {
    return shoppingListRepository.findWithLinesById(id);
  }

  @Override
  public ShoppingList saveAndFlush(ShoppingList list) {
    return shoppingListRepository.saveAndFlush(list);
  }

  @Override
  public List<PackSizeHeuristic> findPacksByKey(String ingredientMappingKey) {
    return packSizeHeuristicRepository.findAllByIngredientMappingKeyOrderByRankAsc(
        ingredientMappingKey);
  }

  @Override
  public List<PackSizeHeuristic> findPacksByCategory(String category) {
    return packSizeHeuristicRepository.findAllByCategoryOrderByRankAsc(category);
  }
}
