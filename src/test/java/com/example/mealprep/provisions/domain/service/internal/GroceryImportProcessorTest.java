package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.GroceryOrderLine;
import com.example.mealprep.provisions.api.dto.GroceryOrderSubstitution;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.ProvisionGroceryImportLogRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link GroceryImportProcessor}. Lives in the processor's internal package so it can
 * construct the package-private type directly. Mocks for repositories + collaborators; the
 * MapStruct-generated mapper is the real impl (deterministic, no-I/O).
 */
@ExtendWith(MockitoExtension.class)
class GroceryImportProcessorTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private SupplierProductRepository supplierProductRepository;
  @Mock private ProvisionGroceryImportLogRepository importLogRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private ExpiryInferenceService expiryInference;
  @Mock private ProvisionEventBatcher eventBatcher;

  private final InventoryItemMapper inventoryMapper =
      new com.example.mealprep.provisions.api.mapper.InventoryItemMapperImpl();
  private final SupplierProductMapper supplierMapper = new SupplierProductMapper() {};
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private GroceryImportProcessor newProcessor() {
    return new GroceryImportProcessor(
        inventoryItemRepository,
        supplierProductRepository,
        importLogRepository,
        auditLogRepository,
        expiryInference,
        eventBatcher,
        inventoryMapper,
        supplierMapper,
        objectMapper);
  }

  private void stubInventorySaveAndFlushPassthrough() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private void stubSupplierSaveAndFlushPassthrough() {
    when(supplierProductRepository.saveAndFlush(any(SupplierProduct.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void process_throwsDuplicate_whenLogExistsForUserSourceRef() {
    UUID userId = UUID.randomUUID();
    GroceryOrderImportCommand cmd = ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-1");
    when(importLogRepository.existsByIdUserIdAndIdSourceAndIdSourceRef(
            eq(userId), eq(ItemSource.TESCO_ORDER), eq("ord-1")))
        .thenReturn(true);

    assertThatThrownBy(() -> newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT))
        .isInstanceOf(DuplicateGroceryImportException.class);

    verify(importLogRepository, never()).save(any());
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
  }

  @Test
  void process_singleLineWithNoExistingRow_addsItem_andCoalescesEvent() {
    UUID userId = UUID.randomUUID();
    GroceryOrderImportCommand cmd = ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-1");

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(anyString(), anyString(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
            eq(userId), eq("cheese:cheddar"), eq(StorageLocation.FRIDGE), eq(null)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    assertThat(result.addedItems()).hasSize(1);
    assertThat(result.mergedItems()).isEmpty();
    assertThat(result.updatedSupplierProducts()).hasSize(1);
    assertThat(result.warnings()).isEmpty();
    verify(importLogRepository, times(1)).save(any());
    verify(auditLogRepository, times(1)).save(any(InventoryAuditLog.class));
    verify(eventBatcher, times(1))
        .recordItemAddedFromGrocery(eq(userId), any(), eq("tesco"), eq("ord-1"), any());
  }

  @Test
  void process_singleLineMatchingExistingRow_merges_andSumsQuantity() {
    UUID userId = UUID.randomUUID();
    GroceryOrderImportCommand cmd = ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-1");

    InventoryItem existing =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .ingredientMappingKey("cheese:cheddar")
            .storageLocation(StorageLocation.FRIDGE)
            .costPaid(new BigDecimal("2.00"))
            .build();

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(anyString(), anyString(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
            eq(userId), eq("cheese:cheddar"), eq(StorageLocation.FRIDGE), eq(null)))
        .thenReturn(Optional.of(existing));
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    assertThat(result.addedItems()).isEmpty();
    assertThat(result.mergedItems()).hasSize(1);
    assertThat(existing.getQuantity()).isEqualByComparingTo("350.000");
    assertThat(existing.getCostPaid()).isEqualByComparingTo("5.49");
    verify(auditLogRepository, times(1)).save(any(InventoryAuditLog.class));
  }

  @Test
  void process_lineWithNullMappingKey_alwaysCreatesNewRow_neverMerges() {
    UUID userId = UUID.randomUUID();
    GroceryOrderLine nullKeyLine =
        new GroceryOrderLine(
            "tesco:misc",
            "Miscellaneous",
            null,
            new BigDecimal("100.000"),
            "g",
            new BigDecimal("1.00"),
            "uncategorised",
            100);
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-1", List.of(nullKeyLine));

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    assertThat(result.addedItems()).hasSize(1);
    assertThat(result.mergedItems()).isEmpty();
    verify(inventoryItemRepository, never())
        .findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
            any(), any(), any(), any());
  }

  @Test
  void process_substitutionTargetingCachedProduct_appendsSubstitutionRecord() {
    UUID userId = UUID.randomUUID();
    SupplierProduct cached =
        ProvisionsTestData.supplierProduct("tesco", "tesco:ordered-sku")
            .substitutionHistory(new ArrayList<>())
            .build();
    GroceryOrderSubstitution sub =
        ProvisionsTestData.groceryOrderSubstitution(
            "tesco:ordered-sku", "tesco:substituted-sku", "out of stock");
    GroceryOrderImportCommand cmd =
        new GroceryOrderImportCommand(
            "tesco",
            "ord-sub-1",
            LocalDate.parse("2026-05-10"),
            List.of(ProvisionsTestData.groceryOrderLine()),
            List.of(sub),
            null);

    when(supplierProductRepository.findBySupplierAndProductId("tesco", "tesco:cheddar-250g"))
        .thenReturn(Optional.empty());
    when(supplierProductRepository.findBySupplierAndProductId("tesco", "tesco:ordered-sku"))
        .thenReturn(Optional.of(cached));
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    assertThat(result.warnings()).isEmpty();
    assertThat(cached.getSubstitutionHistory()).hasSize(1);
    assertThat(cached.getSubstitutionHistory().get(0).substitutedWithProductId())
        .isEqualTo("tesco:substituted-sku");
  }

  @Test
  void process_substitutionTargetingUncachedProduct_emitsWarning_andContinues() {
    UUID userId = UUID.randomUUID();
    GroceryOrderSubstitution sub =
        ProvisionsTestData.groceryOrderSubstitution(
            "tesco:not-cached-sku", "tesco:substituted-sku", "out of stock");
    GroceryOrderImportCommand cmd =
        new GroceryOrderImportCommand(
            "tesco",
            "ord-sub-1",
            LocalDate.parse("2026-05-10"),
            List.of(ProvisionsTestData.groceryOrderLine()),
            List.of(sub),
            null);

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).contains("tesco:not-cached-sku");
    assertThat(result.addedItems()).hasSize(1);
  }

  @Test
  void process_supplierTesco_resolvesToTescoOrderSource() {
    UUID userId = UUID.randomUUID();
    GroceryOrderImportCommand cmd = ProvisionsTestData.groceryOrderImportCommand("TESCO", "ord-1");

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    verify(importLogRepository, times(1))
        .existsByIdUserIdAndIdSourceAndIdSourceRef(
            eq(userId), eq(ItemSource.TESCO_ORDER), eq("ord-1"));
  }

  @Test
  void process_supplierNonTesco_resolvesToOtherShopSource() {
    UUID userId = UUID.randomUUID();
    GroceryOrderImportCommand cmd = ProvisionsTestData.groceryOrderImportCommand("aldi", "ord-1");

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);

    verify(importLogRepository, times(1))
        .existsByIdUserIdAndIdSourceAndIdSourceRef(
            eq(userId), eq(ItemSource.OTHER_SHOP), eq("ord-1"));
  }

  @Test
  void process_inferLocation_freezerCategoryToFreezer() {
    UUID userId = UUID.randomUUID();
    GroceryOrderLine frozen =
        ProvisionsTestData.groceryOrderLine(
            "tesco:frozen-peas", "veg:peas", "frozen", new BigDecimal("500.000"));
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-frz", List.of(frozen));

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
            eq(userId), eq("veg:peas"), eq(StorageLocation.FREEZER), eq(null)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);
    assertThat(result.addedItems()).hasSize(1);
    assertThat(result.addedItems().get(0).storageLocation()).isEqualTo(StorageLocation.FREEZER);
  }

  @Test
  void process_inferLocation_unknownCategoryDefaultsToCupboard() {
    UUID userId = UUID.randomUUID();
    GroceryOrderLine misc =
        ProvisionsTestData.groceryOrderLine(
            "tesco:rice", "grain:rice", "grain", new BigDecimal("1000.000"));
    GroceryOrderImportCommand cmd =
        ProvisionsTestData.groceryOrderImportCommand("tesco", "ord-rc", List.of(misc));

    when(supplierProductRepository.findBySupplierAndProductId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    stubSupplierSaveAndFlushPassthrough();
    when(expiryInference.inferExpiry(any(), any(), any(LocalDate.class)))
        .thenReturn(Optional.empty());
    when(inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
            eq(userId), eq("grain:rice"), eq(StorageLocation.CUPBOARD), eq(null)))
        .thenReturn(Optional.empty());
    stubInventorySaveAndFlushPassthrough();

    GroceryImportResultDto result = newProcessor().process(userId, cmd, AuditActor.GROCERY_IMPORT);
    assertThat(result.addedItems().get(0).storageLocation()).isEqualTo(StorageLocation.CUPBOARD);
    assertThat(result.addedItems().get(0).itemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);
  }
}
