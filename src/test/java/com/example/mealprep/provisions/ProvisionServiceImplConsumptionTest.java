package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.provisions.api.dto.AdjustInventoryQuantityRequest;
import com.example.mealprep.provisions.api.dto.CookEventCommand;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.api.dto.InventoryDeductionResultDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.LogWasteRequest;
import com.example.mealprep.provisions.api.dto.MealConsumptionCommand;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.dto.ReasonAggregateRow;
import com.example.mealprep.provisions.api.dto.RecipeIngredientUsage;
import com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.TopWastedItemDto;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.api.dto.WasteReason;
import com.example.mealprep.provisions.api.dto.WasteSummaryDto;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.api.mapper.WasteEntryMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.Equipment;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.ProvisionCookEventDedupe;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import com.example.mealprep.provisions.domain.repository.BudgetRepository;
import com.example.mealprep.provisions.domain.repository.CookEventDedupeRepository;
import com.example.mealprep.provisions.domain.repository.EquipmentRepository;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.domain.repository.WasteEntryRepository;
import com.example.mealprep.provisions.domain.service.internal.ProvisionServiceImpl;
import com.example.mealprep.provisions.event.InventoryItemUpsertedEvent;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.example.mealprep.provisions.exception.InventoryUnderflowException;
import com.example.mealprep.provisions.exception.WasteExceedsInventoryException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for the consumption-side surface of {@link ProvisionServiceImpl}: quantity adjustment,
 * waste logging and its inventory deduction, the cook-event / meal-consumption /
 * standalone-consumption flows, grocery import gating and line reversal, plus the read paths the
 * sibling test does not touch. Same fixture approach as {@link ProvisionServiceImplTest}:
 * repositories and event publisher mocked at the module boundary, real MapStruct mappers, fixed
 * clock.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionServiceImplConsumptionTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private EquipmentRepository equipmentRepository;
  @Mock private BudgetRepository budgetRepository;
  @Mock private SupplierProductRepository supplierProductRepository;
  @Mock private WasteEntryRepository wasteEntryRepository;
  @Mock private CookEventDedupeRepository cookEventDedupeRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private LifestyleConfigQueryService lifestyleConfigQueryService;

  private final InventoryItemMapper mapper =
      new com.example.mealprep.provisions.api.mapper.InventoryItemMapperImpl();
  private final EquipmentMapper equipmentMapper =
      new com.example.mealprep.provisions.api.mapper.EquipmentMapperImpl();
  private final BudgetMapper budgetMapper = new BudgetMapper() {};
  private final InventoryAuditMapper inventoryAuditMapper =
      new com.example.mealprep.provisions.api.mapper.InventoryAuditMapperImpl();
  private final SupplierProductMapper supplierProductMapper = new SupplierProductMapper() {};
  private final WasteEntryMapper wasteEntryMapper = new WasteEntryMapper() {};

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private static final Instant NOW = Instant.parse("2026-05-09T10:00:00Z");
  private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final Pageable PAGE = PageRequest.of(0, 20);

  private static final String INTERNAL_PACKAGE =
      "com.example.mealprep.provisions.domain.service.internal.";

  private ProvisionServiceImpl service() {
    return new ProvisionServiceImpl(
        inventoryItemRepository,
        auditLogRepository,
        equipmentRepository,
        budgetRepository,
        supplierProductRepository,
        wasteEntryRepository,
        cookEventDedupeRepository,
        mapper,
        equipmentMapper,
        budgetMapper,
        inventoryAuditMapper,
        supplierProductMapper,
        wasteEntryMapper,
        eventPublisher,
        objectMapper,
        fixedClock,
        householdQueryService,
        null,
        null,
        null,
        null,
        lifestyleConfigQueryService);
  }

  // The cook and consumption flows lean on package-private collaborators in
  // domain.service.internal, which this package cannot name. The real event batcher and deduction
  // engine are therefore built reflectively around the same mocks; outside a transaction the
  // batcher publishes straight through the mocked ApplicationEventPublisher, so events stay
  // observable. The grocery processor is a reflective stub.
  private ProvisionServiceImpl serviceWith(
      Object deductionEngine, Object eventBatcher, Object groceryImportProcessor) {
    try {
      Constructor<?> ctor = ProvisionServiceImpl.class.getConstructors()[0];
      return (ProvisionServiceImpl)
          ctor.newInstance(
              inventoryItemRepository,
              auditLogRepository,
              equipmentRepository,
              budgetRepository,
              supplierProductRepository,
              wasteEntryRepository,
              cookEventDedupeRepository,
              mapper,
              equipmentMapper,
              budgetMapper,
              inventoryAuditMapper,
              supplierProductMapper,
              wasteEntryMapper,
              eventPublisher,
              objectMapper,
              fixedClock,
              householdQueryService,
              deductionEngine,
              eventBatcher,
              groceryImportProcessor,
              null,
              lifestyleConfigQueryService);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object newEventBatcher() {
    try {
      Class<?> type = Class.forName(INTERNAL_PACKAGE + "ProvisionEventBatcher");
      Constructor<?> ctor = type.getDeclaredConstructor(ApplicationEventPublisher.class);
      ctor.setAccessible(true);
      return ctor.newInstance(eventPublisher);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object newDeductionEngine(Object eventBatcher) {
    try {
      Class<?> type = Class.forName(INTERNAL_PACKAGE + "InventoryDeductionEngine");
      Constructor<?> ctor = type.getDeclaredConstructors()[0];
      ctor.setAccessible(true);
      return ctor.newInstance(
          inventoryItemRepository, auditLogRepository, eventBatcher, objectMapper, fixedClock);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object groceryProcessorReturning(GroceryImportResultDto result) {
    try {
      Class<?> type = Class.forName(INTERNAL_PACKAGE + "GroceryImportProcessor");
      return Mockito.mock(
          type,
          invocation ->
              "process".equals(invocation.getMethod().getName())
                  ? result
                  : Answers.RETURNS_DEFAULTS.answer(invocation));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private ProvisionServiceImpl consumptionService() {
    return serviceWith(null, newEventBatcher(), null);
  }

  private ProvisionServiceImpl cookService() {
    Object batcher = newEventBatcher();
    return serviceWith(newDeductionEngine(batcher), batcher, null);
  }

  private static LifestyleConfigDto lifestyleConfig(UUID userId, boolean pantryEnabled) {
    LifestyleConfigDocument document =
        new LifestyleConfigDocument(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new LifestyleConfigDocument.PantryTracking(pantryEnabled));
    return new LifestyleConfigDto(UUID.randomUUID(), userId, document, null, 0L, null, null);
  }

  private void stubItemLookup(UUID userId, InventoryItem item) {
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
  }

  private void stubSaveAndFlushPassthrough() {
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  // ---------------- adjustQuantity ----------------

  @Test
  void adjustQuantity_whenItemMissing_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .adjustQuantity(
                        itemId,
                        userId,
                        new AdjustInventoryQuantityRequest(new BigDecimal("1.000"), 0L)))
        .isInstanceOf(InventoryItemNotFoundException.class);
  }

  @Test
  void adjustQuantity_whenVersionMismatch_throws409_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(3);
    stubItemLookup(userId, item);

    assertThatThrownBy(
            () ->
                service()
                    .adjustQuantity(
                        item.getId(),
                        userId,
                        new AdjustInventoryQuantityRequest(new BigDecimal("1.000"), 0L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustQuantity_onStatusTrackedItem_throws400() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build();
    item.setVersion(0);
    stubItemLookup(userId, item);

    assertThatThrownBy(
            () ->
                service()
                    .adjustQuantity(
                        item.getId(),
                        userId,
                        new AdjustInventoryQuantityRequest(new BigDecimal("1.000"), 0L)))
        .isInstanceOf(InvalidInventoryQuantityException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
  }

  @Test
  void adjustQuantity_sameQuantity_isNoOp_andStillReturnsDto() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    item.setVersion(0);
    stubItemLookup(userId, item);

    InventoryItemDto result =
        service()
            .adjustQuantity(
                item.getId(),
                userId,
                new AdjustInventoryQuantityRequest(new BigDecimal("250.000"), 0L));

    assertThat(result).isNotNull();
    assertThat(result.quantity()).isEqualByComparingTo("250.000");
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustQuantity_decrement_setsQuantity_auditsBothValues_publishesManualEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    item.setVersion(0);
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();

    UUID traceId = UUID.fromString("7e57ab1e-0000-4000-8000-000000000001");
    MDC.put("traceId", traceId.toString());
    InventoryItemDto result;
    try {
      result =
          service()
              .adjustQuantity(
                  item.getId(),
                  userId,
                  new AdjustInventoryQuantityRequest(new BigDecimal("100.000"), 0L));
    } finally {
      MDC.remove("traceId");
    }

    assertThat(result.quantity()).isEqualByComparingTo("100.000");
    assertThat(item.getQuantity()).isEqualByComparingTo("100.000");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getFieldChanged()).isEqualTo("quantity");
    assertThat(audit.getValue().getActor()).isEqualTo(AuditActor.USER);
    assertThat(audit.getValue().getActorUserId()).isEqualTo(userId);
    assertThat(audit.getValue().getPreviousValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("250.000");
    assertThat(audit.getValue().getNewValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("100.000");

    ArgumentCaptor<ItemQuantityAdjustedEvent> event =
        ArgumentCaptor.forClass(ItemQuantityAdjustedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().userId()).isEqualTo(userId);
    assertThat(event.getValue().affectedItemIds()).containsExactly(item.getId());
    assertThat(event.getValue().source()).isEqualTo(ItemAdjustmentSource.MANUAL);
    // The MDC trace id must flow through unchanged.
    assertThat(event.getValue().traceId()).isEqualTo(traceId);
    assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
  }

  @Test
  void adjustQuantity_toZero_marksExhausted() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0);
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();

    InventoryItemDto result =
        service()
            .adjustQuantity(
                item.getId(),
                userId,
                new AdjustInventoryQuantityRequest(new BigDecimal("0.000"), 0L));

    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(result.quantity()).isEqualByComparingTo("0");
  }

  // ---------------- markExhausted ----------------

  @Test
  void markExhausted_whenItemMissing_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().markExhausted(itemId, userId))
        .isInstanceOf(InventoryItemNotFoundException.class);
  }

  // ---------------- logWaste ----------------

  @Test
  void logWaste_freeForm_savesEntry_neverTouchesInventory() {
    UUID userId = UUID.randomUUID();
    when(wasteEntryRepository.saveAndFlush(any(WasteEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    WasteEntryDto result = service().logWaste(userId, ProvisionsTestData.logWasteRequestFreeForm());

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.itemName()).isEqualTo("Bunch of celery");
    assertThat(result.reason()).isEqualTo(WasteReason.EXPIRED);
    assertThat(result.costEstimate()).isEqualByComparingTo("1.20");
    assertThat(result.occurredOn()).isEqualTo(LocalDate.parse("2026-05-08"));
    verifyNoInteractions(inventoryItemRepository);
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void logWaste_linkedItemMissing_throws404_savesNothing() {
    UUID userId = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(missing, userId)).thenReturn(Optional.empty());
    LogWasteRequest request = ProvisionsTestData.logWasteRequestLinkedQuantity(missing);

    assertThatThrownBy(() -> service().logWaste(userId, request))
        .isInstanceOf(InventoryItemNotFoundException.class);

    verifyNoInteractions(wasteEntryRepository);
  }

  @Test
  void logWaste_quantityAboveInventory_throws_savesNothing() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);

    LogWasteRequest request =
        new LogWasteRequest(
            item.getId(),
            "Cheddar",
            new BigDecimal("300.000"),
            "g",
            WasteReason.EXPIRED,
            null,
            LocalDate.parse("2026-05-08"),
            null);

    assertThatThrownBy(() -> service().logWaste(userId, request))
        .isInstanceOf(WasteExceedsInventoryException.class);

    verifyNoInteractions(wasteEntryRepository);
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
  }

  @Test
  void logWaste_partialQuantity_decrementsExactly_oneAuditRow_publishesWasteAdjustment() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();
    when(wasteEntryRepository.saveAndFlush(any(WasteEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    WasteEntryDto result =
        service().logWaste(userId, ProvisionsTestData.logWasteRequestLinkedQuantity(item.getId()));

    assertThat(result.quantity()).isEqualByComparingTo("100.000");
    assertThat(result.inventoryItemId()).isEqualTo(item.getId());
    assertThat(item.getQuantity()).isEqualByComparingTo("150.000");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);
    verify(inventoryItemRepository).saveAndFlush(item);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository, times(1)).save(audit.capture());
    assertThat(audit.getValue().getFieldChanged()).isEqualTo("quantity");
    assertThat(audit.getValue().getPreviousValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("250.000");
    assertThat(audit.getValue().getNewValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("150.000");

    ArgumentCaptor<ItemQuantityAdjustedEvent> event =
        ArgumentCaptor.forClass(ItemQuantityAdjustedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().source()).isEqualTo(ItemAdjustmentSource.WASTE);
    assertThat(event.getValue().affectedItemIds()).containsExactly(item.getId());
    assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
  }

  @Test
  void logWaste_fullQuantity_landsExactlyOnZero_marksWasted_writesStatusAuditRow() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();
    when(wasteEntryRepository.saveAndFlush(any(WasteEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    LogWasteRequest request =
        new LogWasteRequest(
            item.getId(),
            "Cheddar",
            new BigDecimal("250.000"),
            "g",
            WasteReason.EXPIRED,
            null,
            LocalDate.parse("2026-05-08"),
            null);

    service().logWaste(userId, request);

    // Exact subtraction, not the floored-at-zero constant: the entity keeps its scale.
    assertThat(item.getQuantity()).isEqualTo(new BigDecimal("0.000"));
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.WASTED);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository, times(2)).save(audit.capture());
    InventoryAuditLog quantityRow = audit.getAllValues().get(0);
    assertThat(quantityRow.getFieldChanged()).isEqualTo("quantity");
    assertThat(quantityRow.getNewValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("0");
    InventoryAuditLog statusRow = audit.getAllValues().get(1);
    assertThat(statusRow.getFieldChanged()).isEqualTo("itemStatus");
    assertThat(statusRow.getPreviousValueJson().get("itemStatus").asText()).isEqualTo("ACTIVE");
    assertThat(statusRow.getNewValueJson().get("itemStatus").asText()).isEqualTo("WASTED");

    verify(eventPublisher).publishEvent(any(ItemQuantityAdjustedEvent.class));
  }

  @Test
  void logWaste_statusTrackedItem_marksWasted_publishesSpoiledEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build();
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();
    when(wasteEntryRepository.saveAndFlush(any(WasteEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().logWaste(userId, ProvisionsTestData.logWasteRequestLinkedStatus(item.getId()));

    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.WASTED);
    verify(inventoryItemRepository).saveAndFlush(item);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getFieldChanged()).isEqualTo("itemStatus");
    assertThat(audit.getValue().getPreviousValueJson().get("itemStatus").asText())
        .isEqualTo("ACTIVE");
    assertThat(audit.getValue().getNewValueJson().get("itemStatus").asText()).isEqualTo("WASTED");

    ArgumentCaptor<ItemSpoiledEvent> event = ArgumentCaptor.forClass(ItemSpoiledEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().reason()).isEqualTo("wasted");
    assertThat(event.getValue().affectedItemIds()).containsExactly(item.getId());
  }

  @Test
  void logWaste_statusTrackedAlreadyWasted_keepsWasteRowButSkipsLifecycleWrite() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.statusTrackedItem(userId).itemStatus(ItemLifecycleStatus.WASTED).build();
    stubItemLookup(userId, item);
    when(wasteEntryRepository.saveAndFlush(any(WasteEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    WasteEntryDto result =
        service().logWaste(userId, ProvisionsTestData.logWasteRequestLinkedStatus(item.getId()));

    assertThat(result).isNotNull();
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- applyCookEvent ----------------

  @Test
  void applyCookEvent_scalesUsageByProportion_returnsUpdatedItems() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("10.000"))
            .ingredientMappingKey("cheese:cheddar")
            .build();
    when(cookEventDedupeRepository.existsByIdMealSlotIdAndIdDedupeKey(mealSlotId, "cook-1"))
        .thenReturn(false);
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));
    stubSaveAndFlushPassthrough();
    when(inventoryItemRepository.findAllById(any())).thenReturn(List.of(item));

    CookEventCommand command =
        new CookEventCommand(
            recipeId,
            null,
            mealSlotId,
            2,
            false,
            new BigDecimal("0.5"),
            null,
            "cook-1",
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("4"), "g")),
            null,
            null,
            null);

    InventoryDeductionResultDto result = cookService().applyCookEvent(userId, command);

    // 4 g at proportion 0.5 takes 2 g off the 10 g row.
    assertThat(result.updatedItems()).hasSize(1);
    assertThat(result.updatedItems().get(0).quantity()).isEqualByComparingTo("8.000");
    assertThat(result.exhaustedItems()).isEmpty();
    assertThat(result.underflows()).isEmpty();
    verify(cookEventDedupeRepository).save(any(ProvisionCookEventDedupe.class));
  }

  @Test
  void applyCookEvent_duplicateReplay_isNoOp_andDerivesAStableTruncatedKey() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();
    when(cookEventDedupeRepository.existsByIdMealSlotIdAndIdDedupeKey(eq(mealSlotId), anyString()))
        .thenReturn(true);

    CookEventCommand command =
        new CookEventCommand(
            recipeId,
            null,
            mealSlotId,
            3,
            false,
            null,
            null,
            null,
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("4"), "g")),
            null,
            null,
            null);

    InventoryDeductionResultDto result = service().applyCookEvent(userId, command);

    assertThat(result).isNotNull();
    assertThat(result.updatedItems()).isEmpty();
    assertThat(result.exhaustedItems()).isEmpty();
    assertThat(result.underflows()).isEmpty();

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(cookEventDedupeRepository)
        .existsByIdMealSlotIdAndIdDedupeKey(eq(mealSlotId), key.capture());
    assertThat(key.getValue()).isEqualTo(expectedDedupeKey(mealSlotId, recipeId, 3));
    verify(cookEventDedupeRepository, never()).save(any(ProvisionCookEventDedupe.class));
    verifyNoInteractions(inventoryItemRepository);
  }

  // Same derivation the service uses, pinned here so a change in the material or the 32-char
  // truncation shows up.
  private static String expectedDedupeKey(UUID mealSlotId, UUID recipeId, int servings) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          md.digest(
              (mealSlotId + "|" + recipeId + "|" + servings + "|" + false)
                  .getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void applyCookEvent_strictWithUnderflow_throws() {
    UUID userId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("1.000"))
            .ingredientMappingKey("cheese:cheddar")
            .build();
    when(cookEventDedupeRepository.existsByIdMealSlotIdAndIdDedupeKey(mealSlotId, "cook-2"))
        .thenReturn(false);
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));
    stubSaveAndFlushPassthrough();

    CookEventCommand command =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            mealSlotId,
            1,
            false,
            null,
            true,
            "cook-2",
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("5"), "g")),
            null,
            null,
            null);

    assertThatThrownBy(() -> cookService().applyCookEvent(userId, command))
        .isInstanceOf(InventoryUnderflowException.class);
  }

  @Test
  void applyCookEvent_lenientWithUnderflow_reportsFlagAndExhaustedRow() {
    UUID userId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .quantity(new BigDecimal("1.000"))
            .ingredientMappingKey("cheese:cheddar")
            .build();
    when(cookEventDedupeRepository.existsByIdMealSlotIdAndIdDedupeKey(mealSlotId, "cook-3"))
        .thenReturn(false);
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));
    stubSaveAndFlushPassthrough();
    when(inventoryItemRepository.findAllById(any())).thenReturn(List.of(item));

    CookEventCommand command =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            mealSlotId,
            1,
            false,
            null,
            null,
            "cook-3",
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("5"), "g")),
            null,
            null,
            null);

    InventoryDeductionResultDto result = cookService().applyCookEvent(userId, command);

    assertThat(result.underflows()).hasSize(1);
    assertThat(result.underflows().get(0).ingredientMappingKey()).isEqualTo("cheese:cheddar");
    assertThat(result.underflows().get(0).requested()).isEqualByComparingTo("5");
    assertThat(result.underflows().get(0).available()).isEqualByComparingTo("1");
    assertThat(result.exhaustedItems()).containsExactly(item.getId());
    assertThat(result.updatedItems().get(0).quantity()).isEqualByComparingTo("0");
  }

  // ---------------- applyMealConsumption ----------------

  @Test
  void applyMealConsumption_whenItemMissing_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                consumptionService()
                    .applyMealConsumption(
                        userId, new MealConsumptionCommand(itemId, new BigDecimal("1.000"), null)))
        .isInstanceOf(InventoryItemNotFoundException.class);
  }

  @Test
  void applyMealConsumption_partialPortions_decrementsExactly_publishesBatchedEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();

    UUID traceId = UUID.randomUUID();
    InventoryDeductionResultDto result =
        consumptionService()
            .applyMealConsumption(
                userId,
                new MealConsumptionCommand(item.getId(), new BigDecimal("100.000"), traceId));

    assertThat(result).isNotNull();
    assertThat(result.updatedItems()).hasSize(1);
    assertThat(result.updatedItems().get(0).quantity()).isEqualByComparingTo("150.000");
    assertThat(result.exhaustedItems()).isEmpty();
    assertThat(item.getQuantity()).isEqualByComparingTo("150.000");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getActor()).isEqualTo(AuditActor.COOK_EVENT);
    assertThat(audit.getValue().getActorUserId()).isNull();
    assertThat(audit.getValue().getPreviousValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("250.000");
    assertThat(audit.getValue().getNewValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("150.000");

    ArgumentCaptor<ItemQuantityAdjustedEvent> event =
        ArgumentCaptor.forClass(ItemQuantityAdjustedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().source()).isEqualTo(ItemAdjustmentSource.MEAL_CONSUMPTION);
    assertThat(event.getValue().affectedItemIds()).containsExactly(item.getId());
    assertThat(event.getValue().traceId()).isEqualTo(traceId);
  }

  @Test
  void applyMealConsumption_exactPortions_landsOnZero_marksExhausted() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();

    InventoryDeductionResultDto result =
        consumptionService()
            .applyMealConsumption(
                userId, new MealConsumptionCommand(item.getId(), new BigDecimal("250.000"), null));

    // Exact subtraction, not the floored-at-zero constant: the entity keeps its scale.
    assertThat(item.getQuantity()).isEqualTo(new BigDecimal("0.000"));
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(result.exhaustedItems()).containsExactly(item.getId());
  }

  // ---------------- applyStandaloneConsumption ----------------

  @Test
  void applyStandaloneConsumption_noMatchingRows_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of());

    Optional<InventoryItemDto> result =
        consumptionService()
            .applyStandaloneConsumption(
                userId,
                new StandaloneConsumptionCommand(
                    "cheese:cheddar", new BigDecimal("100.000"), "g", true, null));

    assertThat(result).isEmpty();
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void applyStandaloneConsumption_unconfirmed_previewsOldestRowWithoutMutating() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));

    Optional<InventoryItemDto> result =
        consumptionService()
            .applyStandaloneConsumption(
                userId,
                new StandaloneConsumptionCommand(
                    "cheese:cheddar", new BigDecimal("100.000"), "g", false, null));

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(item.getId());
    assertThat(result.get().quantity()).isEqualByComparingTo("250.000");
    assertThat(item.getQuantity()).isEqualByComparingTo("250.000");
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void applyStandaloneConsumption_confirmed_decrementsOldestRow_publishesStandaloneEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));
    stubSaveAndFlushPassthrough();

    UUID traceId = UUID.randomUUID();
    Optional<InventoryItemDto> result =
        consumptionService()
            .applyStandaloneConsumption(
                userId,
                new StandaloneConsumptionCommand(
                    "cheese:cheddar", new BigDecimal("100.000"), "g", true, traceId));

    assertThat(result).isPresent();
    assertThat(result.get().quantity()).isEqualByComparingTo("150.000");
    assertThat(item.getQuantity()).isEqualByComparingTo("150.000");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getActor()).isEqualTo(AuditActor.NUTRITION_LOGGER);
    assertThat(audit.getValue().getActorUserId()).isNull();
    assertThat(audit.getValue().getPreviousValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("250.000");
    assertThat(audit.getValue().getNewValueJson().get("quantity").decimalValue())
        .isEqualByComparingTo("150.000");

    ArgumentCaptor<ItemQuantityAdjustedEvent> event =
        ArgumentCaptor.forClass(ItemQuantityAdjustedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().source()).isEqualTo(ItemAdjustmentSource.STANDALONE_LOG);
    assertThat(event.getValue().affectedItemIds()).containsExactly(item.getId());
    assertThat(event.getValue().traceId()).isEqualTo(traceId);
  }

  @Test
  void applyStandaloneConsumption_confirmedExact_landsOnZero_marksExhausted() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "cheese:cheddar"))
        .thenReturn(List.of(item));
    stubSaveAndFlushPassthrough();

    Optional<InventoryItemDto> result =
        consumptionService()
            .applyStandaloneConsumption(
                userId,
                new StandaloneConsumptionCommand(
                    "cheese:cheddar", new BigDecimal("250.000"), "g", true, null));

    // Exact subtraction, not the floored-at-zero constant: the entity keeps its scale.
    assertThat(item.getQuantity()).isEqualTo(new BigDecimal("0.000"));
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(result).isPresent();
    assertThat(result.get().itemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
  }

  // ---------------- applyGroceryOrder ----------------

  @Test
  void applyGroceryOrder_pantryTrackingDisabled_returnsEmptyResult_importsNothing() {
    UUID userId = UUID.randomUUID();
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(lifestyleConfig(userId, false)));

    GroceryImportResultDto result =
        service()
            .applyGroceryOrder(
                userId, ProvisionsTestData.groceryOrderImportCommand("tesco", "order-1"));

    assertThat(result).isNotNull();
    assertThat(result.addedItems()).isEmpty();
    assertThat(result.mergedItems()).isEmpty();
    assertThat(result.updatedSupplierProducts()).isEmpty();
    assertThat(result.warnings()).isEmpty();
    verifyNoInteractions(inventoryItemRepository);
  }

  @Test
  void applyGroceryOrder_pantryTrackingEnabled_returnsProcessorResult() {
    UUID userId = UUID.randomUUID();
    GroceryImportResultDto processed =
        new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of("sub not cached"));

    GroceryImportResultDto result =
        serviceWith(null, null, groceryProcessorReturning(processed))
            .applyGroceryOrder(
                userId, ProvisionsTestData.groceryOrderImportCommand("tesco", "order-2"));

    assertThat(result).isSameAs(processed);
  }

  // ---------------- reverseGroceryLineAdd ----------------

  @Test
  void reverseGroceryLineAdd_zeroQuantity_noop_butAudited() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // 250.000 g
    stubItemLookup(userId, item);

    Optional<InventoryItemDto> result =
        service().reverseGroceryLineAdd(item.getId(), BigDecimal.ZERO, "g", userId);

    assertThat(result).isEmpty();
    assertThat(item.getQuantity()).isEqualByComparingTo("250.000");
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("skipped_no_quantity");
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- read paths ----------------

  @Test
  void listActiveInventory_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    when(inventoryItemRepository.findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, null, PAGE))
        .thenReturn(new PageImpl<>(List.of(item)));

    Page<InventoryItemDto> page =
        service().listActiveInventory(userId, InventorySearchCriteria.none(), PAGE);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).id()).isEqualTo(item.getId());
    assertThat(page.getContent().get(0).name()).isEqualTo("Cheddar");
  }

  @Test
  void getEquipment_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    Equipment oven = ProvisionsTestData.equipment(userId, "oven").build();
    when(equipmentRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(oven));

    List<EquipmentDto> result = service().getEquipment(userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name()).isEqualTo("oven");
  }

  @Test
  void getAvailableEquipment_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    Equipment hob = ProvisionsTestData.equipment(userId, "hob").build();
    when(equipmentRepository.findAllByUserIdAndAvailableTrueOrderByNameAsc(userId))
        .thenReturn(List.of(hob));

    List<EquipmentDto> result = service().getAvailableEquipment(userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name()).isEqualTo("hob");
    assertThat(result.get(0).available()).isTrue();
  }

  @Test
  void getInventoryAuditLog_whenItemNotOwned_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getInventoryAuditLog(itemId, userId, PAGE))
        .isInstanceOf(InventoryItemNotFoundException.class);

    verifyNoInteractions(auditLogRepository);
  }

  @Test
  void getInventoryAuditLog_whenOwned_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    stubItemLookup(userId, item);
    InventoryAuditLog row =
        new InventoryAuditLog(
            UUID.randomUUID(),
            item.getId(),
            userId,
            AuditActor.USER,
            userId,
            "quantity",
            objectMapper.valueToTree(java.util.Map.of("quantity", 250)),
            objectMapper.valueToTree(java.util.Map.of("quantity", 100)),
            NOW);
    when(auditLogRepository.findByInventoryItemIdOrderByOccurredAtDesc(item.getId(), PAGE))
        .thenReturn(new PageImpl<>(List.of(row)));

    Page<InventoryAuditEntryDto> page = service().getInventoryAuditLog(item.getId(), userId, PAGE);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).fieldChanged()).isEqualTo("quantity");
    assertThat(page.getContent().get(0).actor()).isEqualTo(AuditActor.USER);
  }

  @Test
  void getBundle_memberWithoutJoinDate_isNotInRampUpWindow() {
    UUID userId = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(), householdId, userId, HouseholdRole.primary, "Me", "me", 1, null, 0L);
    when(householdQueryService.getByUserId(userId))
        .thenReturn(
            Optional.of(
                new HouseholdDto(
                    householdId,
                    "home",
                    userId,
                    List.of(member),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    0L)));

    ProvisionForPlannerBundleDto bundle = service().getBundle(userId);

    // No joinedAt to anchor on, so the ramp-up grace period must not apply.
    assertThat(bundle.staleness().inRampUpWindow()).isFalse();
  }

  @Test
  void getStaleSupplierProducts_pinsOldestFirstSort_andMapsRows() {
    LocalDate cutoff = LocalDate.parse("2026-04-01");
    SupplierProduct sp = ProvisionsTestData.supplierProduct("tesco", "sku-1").build();
    when(supplierProductRepository.findAllByLastCheckedBefore(eq(cutoff), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sp)));

    Page<SupplierProductDto> page =
        service().getStaleSupplierProducts(cutoff, PageRequest.of(1, 5));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).productId()).isEqualTo("sku-1");

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(supplierProductRepository).findAllByLastCheckedBefore(eq(cutoff), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    assertThat(pageable.getValue().getSort().getOrderFor("lastChecked").getDirection())
        .isEqualTo(Sort.Direction.ASC);
  }

  @Test
  void searchSupplierProducts_pinsNewestFirstSort() {
    SupplierProduct sp = ProvisionsTestData.supplierProduct("tesco", "sku-2").build();
    when(supplierProductRepository.search(eq("onion"), eq("tesco"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sp)));

    Page<SupplierProductDto> page =
        service().searchSupplierProducts("onion", "tesco", PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(supplierProductRepository).search(eq("onion"), eq("tesco"), pageable.capture());
    assertThat(pageable.getValue().getSort().getOrderFor("lastChecked").getDirection())
        .isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void getWasteEntries_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    LocalDate from = LocalDate.parse("2026-05-01");
    LocalDate to = LocalDate.parse("2026-05-09");
    WasteEntry entry = ProvisionsTestData.wasteEntry(userId).build();
    when(wasteEntryRepository.findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDesc(
            userId, from, to, PAGE))
        .thenReturn(new PageImpl<>(List.of(entry)));

    Page<WasteEntryDto> page = service().getWasteEntries(userId, from, to, PAGE);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).itemName()).isEqualTo("Cheddar");
    assertThat(page.getContent().get(0).reason()).isEqualTo(WasteReason.EXPIRED);
  }

  @Test
  void getWasteSummary_sumsEntryCounts_andSkipsNullCosts() {
    UUID userId = UUID.randomUUID();
    LocalDate from = LocalDate.parse("2026-05-01");
    LocalDate to = LocalDate.parse("2026-05-09");
    when(wasteEntryRepository.aggregateByReason(userId, from, to))
        .thenReturn(
            List.of(
                new ReasonAggregateRow(WasteReason.EXPIRED, 3, new BigDecimal("2.50")),
                new ReasonAggregateRow(WasteReason.MADE_TOO_MUCH, 2, null)));
    when(wasteEntryRepository.findTopWastedItems(userId, from, to, PageRequest.of(0, 10)))
        .thenReturn(List.of(new TopWastedItemDto("Cheddar", 3, new BigDecimal("2.50"))));

    WasteSummaryDto summary = service().getWasteSummary(userId, from, to);

    assertThat(summary).isNotNull();
    assertThat(summary.from()).isEqualTo(from);
    assertThat(summary.to()).isEqualTo(to);
    assertThat(summary.totalEntries()).isEqualTo(5);
    assertThat(summary.totalCostEstimate()).isEqualByComparingTo("2.50");
    assertThat(summary.countByReason())
        .containsEntry(WasteReason.EXPIRED, 3L)
        .containsEntry(WasteReason.MADE_TOO_MUCH, 2L);
    assertThat(summary.topItems()).hasSize(1);
    assertThat(summary.topItems().get(0).itemName()).isEqualTo("Cheddar");
  }

  @Test
  void getWasteForUserInWindow_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    LocalDate from = LocalDate.parse("2026-05-01");
    LocalDate to = LocalDate.parse("2026-05-09");
    WasteEntry cheese = ProvisionsTestData.wasteEntry(userId).build();
    WasteEntry milk = ProvisionsTestData.wasteEntry(userId).itemName("Milk").build();
    when(wasteEntryRepository.findAllByUserIdAndOccurredOnBetween(
            userId, from, to, PageRequest.of(0, 1000)))
        .thenReturn(List.of(cheese, milk));

    List<WasteEntryDto> result = service().getWasteForUserInWindow(userId, from, to);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).itemName()).isEqualTo("Cheddar");
    assertThat(result.get(1).itemName()).isEqualTo("Milk");
  }

  // ---------------- updateInventoryItem field diff ----------------

  @Test
  void updateInventoryItem_statusTrackedNoOp_writesNothing() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build();
    item.setVersion(0);
    stubItemLookup(userId, item);

    // Byte-identical to the builder defaults; quantity and costPaid stay null on both sides.
    UpdateInventoryItemRequest sameValues =
        new UpdateInventoryItemRequest(
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
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    InventoryItemDto result = service().updateInventoryItem(item.getId(), userId, sameValues);

    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(StapleStatus.STOCKED);
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateInventoryItem_clearingCostPaid_writesOneCostPaidAuditRow() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .expiryDate(LocalDate.parse("2026-06-01"))
            .ingredientMappingKey("cheese:cheddar")
            .build(); // costPaid 3.49
    item.setVersion(0);
    stubItemLookup(userId, item);
    stubSaveAndFlushPassthrough();

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Cheddar",
            "dairy",
            StorageLocation.FRIDGE,
            TrackingMode.QUANTITY,
            new BigDecimal("250.000"),
            "g",
            null, // costPaid cleared, everything else unchanged
            null,
            false,
            LocalDate.parse("2026-06-01"),
            "cheese:cheddar",
            null,
            ItemSource.MANUAL_ADD,
            null,
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository, times(1)).save(audit.capture());
    assertThat(audit.getValue().getFieldChanged()).isEqualTo("costPaid");
    // The audit row must carry the value being cleared.
    assertThat(audit.getValue().getPreviousValueJson()).isNotNull();
    assertThat(audit.getValue().getPreviousValueJson().decimalValue()).isEqualByComparingTo("3.49");

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
  }
}
