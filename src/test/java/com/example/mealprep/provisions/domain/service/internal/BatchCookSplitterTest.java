package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.BatchCookSplitDirective;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link BatchCookSplitter} — default split, explicit override, zero-fridge case,
 * all-to-freezer case, and the null-directive whole-batch-to-fridge fallback. Lives in the internal
 * package so it can construct the package-private splitter directly.
 */
@ExtendWith(MockitoExtension.class)
class BatchCookSplitterTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private ProvisionEventBatcher eventBatcher;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final LocalDate today = LocalDate.parse("2026-05-12");
  private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

  private BatchCookSplitter newSplitter() {
    return new BatchCookSplitter(
        inventoryItemRepository, auditLogRepository, eventBatcher, objectMapper, clock);
  }

  @Test
  void split_explicitDirective_createsFridgeAndFreezerRows() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();

    List<UUID> created =
        newSplitter()
            .split(userId, recipeId, "Sunday chilli", 5, new BatchCookSplitDirective(3, 2, 5, 8));

    assertThat(created).hasSize(2);

    ArgumentCaptor<InventoryItem> rows = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, times(2)).saveAndFlush(rows.capture());
    InventoryItem fridge =
        rows.getAllValues().stream()
            .filter(i -> i.getStorageLocation() == StorageLocation.FRIDGE)
            .findFirst()
            .orElseThrow();
    InventoryItem freezer =
        rows.getAllValues().stream()
            .filter(i -> i.getStorageLocation() == StorageLocation.FREEZER)
            .findFirst()
            .orElseThrow();

    assertThat(fridge.getQuantity()).isEqualByComparingTo("3");
    assertThat(fridge.getUnit()).isEqualTo("portions");
    assertThat(fridge.getSource()).isEqualTo(ItemSource.BATCH_COOK);
    assertThat(fridge.getTrackingMode()).isEqualTo(TrackingMode.QUANTITY);
    assertThat(fridge.getName()).isEqualTo("Sunday chilli");
    assertThat(fridge.getSourceRecipeId()).isEqualTo(recipeId);
    assertThat(fridge.getExpiryDate()).isEqualTo(today.plusDays(5));
    assertThat(fridge.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);

    assertThat(freezer.getQuantity()).isEqualByComparingTo("2");
    assertThat(freezer.getExpiryDate()).isEqualTo(today.plusWeeks(8));
    assertThat(freezer.getFrozenAt()).isEqualTo(today);
    assertThat(freezer.getMaxFreezeWeeks()).isEqualTo(8);
    assertThat(freezer.getDefrostMethod()).isNotNull();

    // One COOK_EVENT audit row + one batcher adjustment per created portion.
    verify(auditLogRepository, times(2)).save(any(InventoryAuditLog.class));
    verify(eventBatcher, times(2))
        .recordAdjustment(
            eq(userId), any(UUID.class), eq(ItemAdjustmentSource.COOK_EVENT), isNull());
  }

  @Test
  void split_defaultDays_appliesSplitterDefaults() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();

    newSplitter().split(userId, recipeId, null, 4, new BatchCookSplitDirective(2, 1, null, null));

    ArgumentCaptor<InventoryItem> rows = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, times(2)).saveAndFlush(rows.capture());
    InventoryItem fridge =
        rows.getAllValues().stream()
            .filter(i -> i.getStorageLocation() == StorageLocation.FRIDGE)
            .findFirst()
            .orElseThrow();
    InventoryItem freezer =
        rows.getAllValues().stream()
            .filter(i -> i.getStorageLocation() == StorageLocation.FREEZER)
            .findFirst()
            .orElseThrow();

    assertThat(fridge.getExpiryDate())
        .isEqualTo(today.plusDays(BatchCookSplitter.DEFAULT_FRIDGE_MAX_DAYS));
    assertThat(freezer.getMaxFreezeWeeks()).isEqualTo(BatchCookSplitter.DEFAULT_FREEZER_MAX_WEEKS);
    // Default label is recipe-derived when none supplied.
    assertThat(fridge.getName()).contains(recipeId.toString());
  }

  @Test
  void split_zeroFridge_createsOnlyFreezerRow() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    UUID userId = UUID.randomUUID();

    List<UUID> created =
        newSplitter()
            .split(
                userId,
                UUID.randomUUID(),
                "Soup",
                4,
                new BatchCookSplitDirective(0, 4, null, null));

    assertThat(created).hasSize(1);
    ArgumentCaptor<InventoryItem> rows = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, times(1)).saveAndFlush(rows.capture());
    assertThat(rows.getValue().getStorageLocation()).isEqualTo(StorageLocation.FREEZER);
    assertThat(rows.getValue().getQuantity()).isEqualByComparingTo("4");
  }

  @Test
  void split_zeroFreezer_createsOnlyFridgeRow() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    UUID userId = UUID.randomUUID();

    List<UUID> created =
        newSplitter()
            .split(
                userId,
                UUID.randomUUID(),
                "Curry",
                3,
                new BatchCookSplitDirective(3, 0, null, null));

    assertThat(created).hasSize(1);
    ArgumentCaptor<InventoryItem> rows = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, times(1)).saveAndFlush(rows.capture());
    assertThat(rows.getValue().getStorageLocation()).isEqualTo(StorageLocation.FRIDGE);
  }

  @Test
  void split_nullDirective_defaultsWholeBatchToFridge() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    UUID userId = UUID.randomUUID();

    List<UUID> created = newSplitter().split(userId, UUID.randomUUID(), "Stew", 6, null);

    assertThat(created).hasSize(1);
    ArgumentCaptor<InventoryItem> rows = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, times(1)).saveAndFlush(rows.capture());
    assertThat(rows.getValue().getStorageLocation()).isEqualTo(StorageLocation.FRIDGE);
    assertThat(rows.getValue().getQuantity()).isEqualByComparingTo("6");
  }

  @Test
  void split_zeroBothPortions_createsNoRows() {
    UUID userId = UUID.randomUUID();

    List<UUID> created =
        newSplitter()
            .split(
                userId,
                UUID.randomUUID(),
                "Nothing",
                0,
                new BatchCookSplitDirective(0, 0, null, null));

    assertThat(created).isEmpty();
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verify(auditLogRepository, never()).save(any(InventoryAuditLog.class));
  }
}
