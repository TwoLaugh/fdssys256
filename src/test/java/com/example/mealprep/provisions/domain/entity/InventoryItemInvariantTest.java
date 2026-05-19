package com.example.mealprep.provisions.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link InventoryItem}'s {@code @PrePersist/@PreUpdate} {@code
 * validateTrackingModeInvariant()} hook (package-private — invoked directly) and the
 * Lombok-generated accessor round-trip.
 *
 * <p>The invariant body had ZERO coverage at baseline (8 NO_COVERAGE conditional mutants on lines
 * 140/144/147). These cases pin every branch: QUANTITY needs both quantity AND unit (each missing
 * piece independently rejected), STATUS needs status, and the cross-mode non-negative-quantity rule
 * (boundary: zero allowed, {@code -0.001} rejected). The accessor round-trip kills the "replace
 * return value with null/0/empty" survivors on the getters.
 */
class InventoryItemInvariantTest {

  private static InventoryItem.InventoryItemBuilder base() {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .name("Cheddar")
        .category("dairy")
        .storageLocation(StorageLocation.FRIDGE)
        .source(ItemSource.MANUAL_ADD)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  @Test
  void quantityMode_withQuantityAndUnit_isValid() {
    InventoryItem item =
        base()
            .trackingMode(TrackingMode.QUANTITY)
            .quantity(new BigDecimal("2.000"))
            .unit("kg")
            .build();
    assertThatCode(item::validateTrackingModeInvariant).doesNotThrowAnyException();
  }

  @Test
  void quantityMode_missingQuantity_throws422() {
    InventoryItem item =
        base().trackingMode(TrackingMode.QUANTITY).quantity(null).unit("kg").build();
    assertThatThrownBy(item::validateTrackingModeInvariant)
        .isInstanceOf(InvalidInventoryQuantityException.class)
        .hasMessageContaining("tracking_mode=QUANTITY requires both quantity and unit");
  }

  @Test
  void quantityMode_missingUnit_throws422() {
    InventoryItem item =
        base()
            .trackingMode(TrackingMode.QUANTITY)
            .quantity(new BigDecimal("1.000"))
            .unit(null)
            .build();
    assertThatThrownBy(item::validateTrackingModeInvariant)
        .isInstanceOf(InvalidInventoryQuantityException.class)
        .hasMessageContaining("requires both quantity and unit");
  }

  @Test
  void statusMode_withStatus_isValid() {
    InventoryItem item =
        base()
            .storageLocation(StorageLocation.SPICE_RACK)
            .trackingMode(TrackingMode.STATUS)
            .status(StapleStatus.STOCKED)
            .build();
    assertThatCode(item::validateTrackingModeInvariant).doesNotThrowAnyException();
  }

  @Test
  void statusMode_missingStatus_throws422() {
    InventoryItem item =
        base()
            .storageLocation(StorageLocation.SPICE_RACK)
            .trackingMode(TrackingMode.STATUS)
            .status(null)
            .build();
    assertThatThrownBy(item::validateTrackingModeInvariant)
        .isInstanceOf(InvalidInventoryQuantityException.class)
        .hasMessageContaining("tracking_mode=STATUS requires status");
  }

  @Test
  void negativeQuantity_throws422_evenWhenModeShapeOtherwiseValid() {
    InventoryItem item =
        base()
            .trackingMode(TrackingMode.QUANTITY)
            .quantity(new BigDecimal("-0.001"))
            .unit("kg")
            .build();
    assertThatThrownBy(item::validateTrackingModeInvariant)
        .isInstanceOf(InvalidInventoryQuantityException.class)
        .hasMessageContaining("quantity must be non-negative");
  }

  @Test
  void zeroQuantity_isAllowed_boundary() {
    InventoryItem item =
        base().trackingMode(TrackingMode.QUANTITY).quantity(BigDecimal.ZERO).unit("kg").build();
    assertThatCode(item::validateTrackingModeInvariant).doesNotThrowAnyException();
  }

  @Test
  void statusMode_withNegativeQuantityPresent_stillRejectsNegative() {
    // status-tracked rows usually have no quantity, but if a negative leaks in the cross-mode
    // guard (line 147) must still fire — it is independent of trackingMode.
    InventoryItem item =
        base()
            .storageLocation(StorageLocation.SPICE_RACK)
            .trackingMode(TrackingMode.STATUS)
            .status(StapleStatus.LOW)
            .quantity(new BigDecimal("-5"))
            .build();
    assertThatThrownBy(item::validateTrackingModeInvariant)
        .isInstanceOf(InvalidInventoryQuantityException.class)
        .hasMessageContaining("quantity must be non-negative");
  }

  @Test
  void accessors_roundTrip_returnConstructedValues() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Instant created = Instant.parse("2026-01-02T03:04:05Z");
    Instant updated = Instant.parse("2026-02-03T04:05:06Z");
    LocalDate expiry = LocalDate.of(2026, 6, 1);
    LocalDate frozen = LocalDate.of(2026, 5, 1);

    InventoryItem item =
        InventoryItem.builder()
            .id(id)
            .userId(userId)
            .name("Salmon")
            .category("fish")
            .storageLocation(StorageLocation.FREEZER)
            .trackingMode(TrackingMode.QUANTITY)
            .quantity(new BigDecimal("3.000"))
            .unit("kg")
            .costPaid(new BigDecimal("12.50"))
            .isStaple(true)
            .expiryDate(expiry)
            .ingredientMappingKey("salmon")
            .source(ItemSource.TESCO_ORDER)
            .sourceRef("order-77")
            .itemStatus(ItemLifecycleStatus.ACTIVE)
            .frozenAt(frozen)
            .maxFreezeWeeks(12)
            .defrostMethod(DefrostMethod.OVERNIGHT_FRIDGE)
            .defrostLeadTimeHours(8)
            .sourceRecipeId(recipeId)
            .version(4L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(item.getCategory()).isEqualTo("fish");
    assertThat(item.isStaple()).isTrue();
    assertThat(item.getExpiryDate()).isEqualTo(expiry);
    assertThat(item.getSourceRef()).isEqualTo("order-77");
    assertThat(item.getFrozenAt()).isEqualTo(frozen);
    assertThat(item.getDefrostMethod()).isEqualTo(DefrostMethod.OVERNIGHT_FRIDGE);
    assertThat(item.getDefrostLeadTimeHours()).isEqualTo(8);
    assertThat(item.getSourceRecipeId()).isEqualTo(recipeId);
    assertThat(item.getVersion()).isEqualTo(4L);
    assertThat(item.getCreatedAt()).isEqualTo(created);
    assertThat(item.getUpdatedAt()).isEqualTo(updated);
  }
}
