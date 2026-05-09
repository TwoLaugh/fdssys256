package com.example.mealprep.provisions.testdata;

import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.domain.entity.DefrostMethod;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Test Data Builder for the provisions module's inventory aggregate. Defaults match the validator +
 * tracking-mode constraints so callers tweak only the field under test.
 */
public final class ProvisionsTestData {

  private ProvisionsTestData() {}

  // ---------------- Builders ----------------

  public static InventoryItem.InventoryItemBuilder quantityTrackedItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Cheddar")
        .category("dairy")
        .storageLocation(StorageLocation.FRIDGE)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(new BigDecimal("250.000"))
        .unit("g")
        .costPaid(new BigDecimal("3.49"))
        .isStaple(false)
        .source(ItemSource.MANUAL_ADD)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  public static InventoryItem.InventoryItemBuilder statusTrackedItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Salt")
        .category("seasoning")
        .storageLocation(StorageLocation.SPICE_RACK)
        .trackingMode(TrackingMode.STATUS)
        .status(StapleStatus.STOCKED)
        .isStaple(true)
        .source(ItemSource.MANUAL_ADD)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  public static InventoryItem.InventoryItemBuilder freezerItem(UUID userId) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name("Frozen Peas")
        .category("vegetable")
        .storageLocation(StorageLocation.FREEZER)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(new BigDecimal("500.000"))
        .unit("g")
        .isStaple(false)
        .source(ItemSource.TESCO_ORDER)
        .itemStatus(ItemLifecycleStatus.ACTIVE)
        .frozenAt(LocalDate.parse("2026-04-01"))
        .maxFreezeWeeks(12)
        .defrostMethod(DefrostMethod.OVERNIGHT_FRIDGE)
        .defrostLeadTimeHours(8);
  }

  // ---------------- Request factories ----------------

  public static CreateInventoryItemRequest createQuantityTrackedRequest() {
    return new CreateInventoryItemRequest(
        "Cheddar",
        "dairy",
        StorageLocation.FRIDGE,
        TrackingMode.QUANTITY,
        new BigDecimal("250.000"),
        "g",
        new BigDecimal("3.49"),
        null,
        false,
        LocalDate.parse("2026-06-01"),
        "cheese:cheddar",
        null,
        ItemSource.MANUAL_ADD,
        null,
        null);
  }

  public static CreateInventoryItemRequest createStatusTrackedRequest() {
    return new CreateInventoryItemRequest(
        "Salt",
        "seasoning",
        StorageLocation.SPICE_RACK,
        TrackingMode.STATUS,
        null,
        null,
        null,
        StapleStatus.STOCKED,
        true,
        null,
        null,
        null,
        ItemSource.MANUAL_ADD,
        null,
        null);
  }

  public static CreateInventoryItemRequest createFreezerRequest() {
    return new CreateInventoryItemRequest(
        "Frozen Peas",
        "vegetable",
        StorageLocation.FREEZER,
        TrackingMode.QUANTITY,
        new BigDecimal("500.000"),
        "g",
        null,
        null,
        false,
        null,
        null,
        null,
        ItemSource.TESCO_ORDER,
        null,
        new FreezerExtensionDto(
            LocalDate.parse("2026-04-01"), 12, DefrostMethod.OVERNIGHT_FRIDGE, 8, null));
  }

  public static UpdateInventoryItemRequest updateQuantityTrackedRequest(long expectedVersion) {
    return new UpdateInventoryItemRequest(
        "Cheddar",
        "dairy",
        StorageLocation.FRIDGE,
        TrackingMode.QUANTITY,
        new BigDecimal("250.000"),
        "g",
        new BigDecimal("3.49"),
        null,
        false,
        LocalDate.parse("2026-06-01"),
        "cheese:cheddar",
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        expectedVersion);
  }
}
