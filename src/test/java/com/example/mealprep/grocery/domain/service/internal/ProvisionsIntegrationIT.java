package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.service.internal.MarkBoughtInventoryBridge.BoughtLine;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-DB integration test (Testcontainers) for the Tier-2 → provisions inventory bridge
 * (grocery-01d). Verifies that {@link MarkBoughtInventoryBridge}'s assembled {@code
 * GroceryOrderImportCommand} actually lands an inventory row through the canonical {@code
 * applyGroceryOrder} path, and that an idempotent re-call with the SAME line-id {@code orderRef}
 * does NOT double-add — provisions rejects the replay with {@link DuplicateGroceryImportException}
 * (the idempotency guarantee) and the inventory quantity stays put.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class ProvisionsIntegrationIT {

  @Autowired private MarkBoughtInventoryBridge bridge;
  @Autowired private ProvisionQueryService provisionQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
  }

  private ShoppingListLine line(String key) {
    ShoppingList list = GroceryTestData.shoppingList().build();
    return GroceryTestData.shoppingListLine()
        .shoppingList(list)
        .ingredientMappingKey(key)
        .displayName("Flour")
        .requestedQuantity(new BigDecimal("1.000"))
        .requestedUnit("kg")
        .suggestedPackSizeG(1000)
        .build();
  }

  @Test
  void applySingle_landsOneInventoryRow_withTheCommandShape() {
    UUID userId = UUID.randomUUID();
    ShoppingListLine l = line("flour");

    GroceryImportResultDto result =
        bridge.applySingle(
            userId,
            "tesco",
            new BoughtLine(l, new BigDecimal("2.000"), "kg", 250),
            UUID.randomUUID());

    assertThat(result.addedItems()).hasSize(1);
    assertThat(MarkBoughtInventoryBridge.firstInventoryItemId(result)).isPresent();

    List<InventoryItemDto> inventory =
        provisionQueryService.getActiveInventoryByMappingKey(userId, "flour");
    assertThat(inventory).hasSize(1);
    InventoryItemDto item = inventory.get(0);
    assertThat(item.quantity()).isEqualByComparingTo(new BigDecimal("2.000"));
    assertThat(item.unit()).isEqualTo("kg");
    // pence (250) → pounds (£2.50) at the boundary.
    assertThat(item.costPaid()).isEqualByComparingTo(new BigDecimal("2.50"));
    assertThat(item.ingredientMappingKey()).isEqualTo("flour");
  }

  @Test
  void applySingle_idempotentReCall_sameLineIdOrderRef_noDoubleAdd() {
    UUID userId = UUID.randomUUID();
    ShoppingListLine l = line("flour");
    BoughtLine bought = new BoughtLine(l, new BigDecimal("1.000"), "kg", 100);

    bridge.applySingle(userId, "tesco", bought, UUID.randomUUID());

    // Re-applying the SAME line-id orderRef is rejected (the (userId, source, orderRef) log key) —
    // this IS the idempotency guarantee: no second inventory row is created.
    assertThatThrownBy(() -> bridge.applySingle(userId, "tesco", bought, UUID.randomUUID()))
        .isInstanceOf(DuplicateGroceryImportException.class);

    List<InventoryItemDto> inventory =
        provisionQueryService.getActiveInventoryByMappingKey(userId, "flour");
    assertThat(inventory).hasSize(1); // NOT doubled
    assertThat(inventory.get(0).quantity()).isEqualByComparingTo(new BigDecimal("1.000"));
  }

  @Test
  void applyBulk_oneCall_landsAllLines_andIsIdempotentPerBatch() {
    UUID userId = UUID.randomUUID();
    ShoppingListLine a = line("flour");
    ShoppingListLine b = line("rice");
    b.setDisplayName("Rice");
    List<BoughtLine> boughtLines =
        List.of(
            new BoughtLine(a, new BigDecimal("1.000"), "kg", 100),
            new BoughtLine(b, new BigDecimal("1.000"), "kg", 200));

    GroceryImportResultDto result =
        bridge.applyBulk(userId, "tesco", boughtLines, UUID.randomUUID());
    assertThat(result.addedItems()).hasSize(2);

    assertThat(provisionQueryService.getActiveInventoryByMappingKey(userId, "flour")).hasSize(1);
    assertThat(provisionQueryService.getActiveInventoryByMappingKey(userId, "rice")).hasSize(1);

    // Replaying the SAME batch (same line-id set → same deterministic orderRef) is rejected.
    assertThatThrownBy(() -> bridge.applyBulk(userId, "tesco", boughtLines, UUID.randomUUID()))
        .isInstanceOf(DuplicateGroceryImportException.class);
    assertThat(provisionQueryService.getActiveInventoryByMappingKey(userId, "flour")).hasSize(1);
  }
}
