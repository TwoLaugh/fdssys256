package com.example.mealprep.grocery;

import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the grocery module's four public service interfaces — cross-module
 * callers (planner, feedback, notification) inject this facade or an individual service rather than
 * reaching into {@code domain.service.*} directly. Per lld/grocery.md line 28 / 66. Mirrors {@code
 * AdaptationModule} / {@code ProvisionsModule}; thin, carries no business logic.
 *
 * <p>{@code @EnableConfigurationProperties(GroceryConfig.class)} binds {@code mealprep.grocery.*}
 * (the project has no {@code @ConfigurationPropertiesScan}, so config records are registered
 * explicitly here). {@code @Lazy} fields break any circular load order should a sibling reference
 * {@code GroceryModule} during its own startup. 01a wires the single {@code GroceryServiceImpl}
 * skeleton behind all four interfaces (every method throws until 01b..01g fill the bodies).
 */
@Component
@EnableConfigurationProperties(GroceryConfig.class)
public class GroceryModule {

  private final ShoppingListService shoppingListService;
  private final ManualFulfilmentService manualFulfilmentService;
  private final GroceryOrderService groceryOrderService;
  private final PriceHistoryService priceHistoryService;

  public GroceryModule(
      @Lazy ShoppingListService shoppingListService,
      @Lazy ManualFulfilmentService manualFulfilmentService,
      @Lazy GroceryOrderService groceryOrderService,
      @Lazy PriceHistoryService priceHistoryService) {
    this.shoppingListService = shoppingListService;
    this.manualFulfilmentService = manualFulfilmentService;
    this.groceryOrderService = groceryOrderService;
    this.priceHistoryService = priceHistoryService;
  }

  /** Tier 1 — shopping list. */
  public ShoppingListService shoppingList() {
    return shoppingListService;
  }

  /** Tier 2 — manual fulfilment (mark-bought). */
  public ManualFulfilmentService manualFulfilment() {
    return manualFulfilmentService;
  }

  /** Tier 3 — grocery order via provider. */
  public GroceryOrderService groceryOrder() {
    return groceryOrderService;
  }

  /** Tier 4 — price history. */
  public PriceHistoryService priceHistory() {
    return priceHistoryService;
  }
}
