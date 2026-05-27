package com.example.mealprep.grocery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.ManualFulfilmentService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full application context with the grocery module wired and asserts the 01a contract:
 *
 * <ul>
 *   <li>all four public service interfaces are resolvable beans (the single {@code
 *       GroceryServiceImpl} skeleton backs all four);
 *   <li>the {@code GroceryModule} facade bean is present and re-exports the four;
 *   <li>{@code GroceryConfig} binds from {@code mealprep.grocery.*} with the LLD defaults.
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class GroceryContextLoadIT {

  @Autowired private ShoppingListService shoppingListService;
  @Autowired private ManualFulfilmentService manualFulfilmentService;
  @Autowired private GroceryOrderService groceryOrderService;
  @Autowired private PriceHistoryService priceHistoryService;
  @Autowired private GroceryModule groceryModule;
  @Autowired private GroceryConfig groceryConfig;

  @Test
  void allFourServiceInterfaces_areResolvable() {
    assertThat(shoppingListService).isNotNull();
    assertThat(manualFulfilmentService).isNotNull();
    assertThat(groceryOrderService).isNotNull();
    assertThat(priceHistoryService).isNotNull();
  }

  @Test
  void groceryModuleFacade_reExportsAllFour() {
    // The facade injects the services @Lazy, so each accessor returns a lazy proxy (a distinct
    // object from the eagerly-@Autowired bean) — assert the contract is wired (non-null and the
    // right interface type) rather than object identity.
    assertThat(groceryModule).isNotNull();
    assertThat(groceryModule.shoppingList()).isInstanceOf(ShoppingListService.class);
    assertThat(groceryModule.manualFulfilment()).isInstanceOf(ManualFulfilmentService.class);
    assertThat(groceryModule.groceryOrder()).isInstanceOf(GroceryOrderService.class);
    assertThat(groceryModule.priceHistory()).isInstanceOf(PriceHistoryService.class);
  }

  @Test
  void tier124ShareOneImpl_tier3IsSeparate() {
    // 01a divergence: Tier 1/2/4 share GroceryServiceImpl; Tier 3 (GroceryOrderService) is a
    // separate GroceryOrderServiceImpl because its getById/getByIds erasure-clash with Tier 1.
    assertThat(shoppingListService).isSameAs(manualFulfilmentService).isSameAs(priceHistoryService);
    assertThat(groceryOrderService).isNotSameAs(shoppingListService);
  }

  @Test
  void groceryConfig_bindsLldDefaults() {
    assertThat(groceryConfig).isNotNull();
    assertThat(groceryConfig.aggregator().halfLifeDays()).isEqualTo(90);
    assertThat(groceryConfig.aggregator().staleThresholdDays()).isEqualTo(90);
    assertThat(groceryConfig.confidenceWeights().paid()).isEqualTo(1.0);
    assertThat(groceryConfig.confidenceWeights().quote()).isEqualTo(0.85);
    assertThat(groceryConfig.confidenceWeights().manual()).isEqualTo(0.7);
    assertThat(groceryConfig.confidenceWeights().manualEstimated()).isEqualTo(0.4);
    assertThat(groceryConfig.confidenceWeights().inflationIndexed()).isEqualTo(0.15);
    assertThat(groceryConfig.inflation().monthlyFactor()).isEqualTo(0.005);
    assertThat(groceryConfig.freshness().defaultRefreshTopN()).isEqualTo(50);
    assertThat(groceryConfig.order().singleFlightLockTtlSeconds()).isEqualTo(300);
  }
}
