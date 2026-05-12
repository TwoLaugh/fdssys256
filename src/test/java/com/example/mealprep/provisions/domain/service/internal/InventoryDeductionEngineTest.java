package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.UnderflowFlagDto;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link InventoryDeductionEngine} — exercises FIFO-by-expiry walk, floor-at-zero
 * underflow, staple OUT transition, and unit-mismatch behaviour. Lives in the engine's internal
 * package so it can construct package-private types directly.
 */
@ExtendWith(MockitoExtension.class)
class InventoryDeductionEngineTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private ProvisionEventBatcher eventBatcher;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

  private InventoryDeductionEngine newEngine() {
    return new InventoryDeductionEngine(
        inventoryItemRepository, auditLogRepository, eventBatcher, objectMapper, clock);
  }

  @Test
  void deduct_fifoByExpiry_walksOldestFirst() {
    UUID userId = UUID.randomUUID();
    String key = "cheese:cheddar";

    InventoryItem oldest =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .expiryDate(LocalDate.parse("2026-05-15"))
            .build();
    InventoryItem mid =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .expiryDate(LocalDate.parse("2026-06-01"))
            .build();

    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of(oldest, mid));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("150.000"), "g", UUID.randomUUID());

    assertThat(o.deductedItemIds()).containsExactly(oldest.getId(), mid.getId());
    assertThat(o.exhaustedItemIds()).containsExactly(oldest.getId());
    assertThat(o.underflows()).isEmpty();
    assertThat(oldest.getQuantity()).isEqualByComparingTo("0");
    assertThat(oldest.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(mid.getQuantity()).isEqualByComparingTo("50.000");
    verify(auditLogRepository, times(2)).save(any(InventoryAuditLog.class));
  }

  @Test
  void deduct_floorAtZero_emitsUnderflowFlag() {
    UUID userId = UUID.randomUUID();
    String key = "cheese:cheddar";

    InventoryItem only =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("30.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .build();

    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of(only));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("100.000"), "g", UUID.randomUUID());

    assertThat(o.deductedItemIds()).hasSize(1);
    assertThat(o.exhaustedItemIds()).hasSize(1);
    assertThat(o.underflows()).hasSize(1);
    UnderflowFlagDto flag = o.underflows().get(0);
    assertThat(flag.ingredientMappingKey()).isEqualTo(key);
    assertThat(flag.requested()).isEqualByComparingTo("100.000");
    assertThat(flag.available()).isEqualByComparingTo("30.000");
  }

  @Test
  void deduct_noMatchingRows_returnsZeroAvailableUnderflow() {
    UUID userId = UUID.randomUUID();
    String key = "missing:thing";
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of());

    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("50.000"), "g", UUID.randomUUID());

    assertThat(o.deductedItemIds()).isEmpty();
    assertThat(o.exhaustedItemIds()).isEmpty();
    assertThat(o.underflows()).hasSize(1);
    assertThat(o.underflows().get(0).available()).isEqualByComparingTo("0");
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
  }

  @Test
  void deduct_staple_hitsOut_recordsRanOut() {
    UUID userId = UUID.randomUUID();
    String key = "spice:salt-staple";

    InventoryItem staple =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("50.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .isStaple(true)
            .status(StapleStatus.STOCKED)
            .build();

    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of(staple));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UUID traceId = UUID.randomUUID();
    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("50.000"), "g", traceId);

    assertThat(o.exhaustedItemIds()).containsExactly(staple.getId());
    assertThat(staple.getStatus()).isEqualTo(StapleStatus.OUT);
    verify(eventBatcher)
        .recordAdjustment(
            eq(userId), eq(staple.getId()), eq(ItemAdjustmentSource.COOK_EVENT), eq(traceId));
    verify(eventBatcher)
        .recordRanOut(eq(userId), eq(staple.getId()), eq(key), anyBoolean(), eq(traceId));
  }

  @Test
  void deduct_unitMismatch_skipsRow_emitsUnderflow() {
    UUID userId = UUID.randomUUID();
    String key = "cheese:cheddar";

    InventoryItem inGrams =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("200.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .build();

    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of(inGrams));

    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("2.000"), "kg", UUID.randomUUID());

    assertThat(o.deductedItemIds()).isEmpty();
    assertThat(o.underflows()).hasSize(1);
    assertThat(o.underflows().get(0).available()).isEqualByComparingTo("0");
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verify(eventBatcher, never())
        .recordAdjustment(
            any(UUID.class), any(UUID.class), any(ItemAdjustmentSource.class), any(UUID.class));
  }

  @Test
  void deduct_satisfiedByFirstRow_secondUntouched() {
    UUID userId = UUID.randomUUID();
    String key = "cheese:cheddar";

    InventoryItem first =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("200.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .build();
    InventoryItem second =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("500.000"))
            .unit("g")
            .ingredientMappingKey(key)
            .build();

    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, key))
        .thenReturn(List.of(first, second));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryDeductionEngine.DeductionOutcome o =
        newEngine().deduct(userId, key, new BigDecimal("50.000"), "g", UUID.randomUUID());

    assertThat(o.deductedItemIds()).containsExactly(first.getId());
    assertThat(o.exhaustedItemIds()).isEmpty();
    assertThat(first.getQuantity()).isEqualByComparingTo("150.000");
    assertThat(second.getQuantity()).isEqualByComparingTo("500.000");
    verify(inventoryItemRepository, times(1)).saveAndFlush(any(InventoryItem.class));
    verify(eventBatcher, never())
        .recordRanOut(any(UUID.class), any(UUID.class), anyString(), anyBoolean(), any(UUID.class));
  }
}
