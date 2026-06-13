package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.AdjustInventoryStatusRequest;
import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.Equipment;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.repository.BudgetRepository;
import com.example.mealprep.provisions.domain.repository.EquipmentRepository;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.domain.service.internal.ProvisionServiceImpl;
import com.example.mealprep.provisions.event.EquipmentChangedEvent;
import com.example.mealprep.provisions.event.InventoryItemUpsertedEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for {@link ProvisionServiceImpl}. Repositories and event publisher are mocked at the
 * module boundary; the real {@link InventoryItemMapper} (MapStruct-generated) is used because it is
 * deterministic, no-I/O, and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionServiceImplTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private EquipmentRepository equipmentRepository;
  @Mock private BudgetRepository budgetRepository;
  @Mock private SupplierProductRepository supplierProductRepository;

  @Mock
  private com.example.mealprep.provisions.domain.repository.WasteEntryRepository
      wasteEntryRepository;

  @Mock private ApplicationEventPublisher eventPublisher;

  @Mock
  private com.example.mealprep.household.domain.service.HouseholdQueryService householdQueryService;

  @Mock
  private com.example.mealprep.preference.domain.service.LifestyleConfigQueryService
      lifestyleConfigQueryService;

  private final InventoryItemMapper mapper =
      new com.example.mealprep.provisions.api.mapper.InventoryItemMapperImpl();
  private final EquipmentMapper equipmentMapper =
      new com.example.mealprep.provisions.api.mapper.EquipmentMapperImpl();
  private final BudgetMapper budgetMapper = new BudgetMapper() {};
  private final InventoryAuditMapper inventoryAuditMapper =
      new com.example.mealprep.provisions.api.mapper.InventoryAuditMapperImpl();
  private final SupplierProductMapper supplierProductMapper = new SupplierProductMapper() {};
  private final com.example.mealprep.provisions.api.mapper.WasteEntryMapper wasteEntryMapper =
      new com.example.mealprep.provisions.api.mapper.WasteEntryMapper() {};

  // Use findAndRegisterModules() so JSR-310 (Instant, LocalDate) serializes correctly without
  // a hard import dependency on jackson-datatype-jsr310 from this test class.
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private ProvisionServiceImpl service() {
    return new ProvisionServiceImpl(
        inventoryItemRepository,
        auditLogRepository,
        equipmentRepository,
        budgetRepository,
        supplierProductRepository,
        wasteEntryRepository,
        null,
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

  // ---------------- getInventoryItem ----------------

  @Test
  void getInventoryItem_whenOwnedByUser_returnsDto() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    Optional<InventoryItemDto> result = service().getInventoryItem(item.getId(), userId);

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(item.getId());
    assertThat(result.get().userId()).isEqualTo(userId);
  }

  @Test
  void getInventoryItem_whenOwnedByAnotherUser_returnsEmpty() {
    UUID otherUserId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, otherUserId))
        .thenReturn(Optional.empty());

    assertThat(service().getInventoryItem(itemId, otherUserId)).isEmpty();
  }

  // ---------------- createInventoryItem ----------------

  @Test
  void createInventoryItem_persistsItem_writesAuditRow_publishesEvent() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(
            inv -> {
              InventoryItem captured = inv.getArgument(0);
              captured.setVersion(0);
              return captured;
            });

    InventoryItemDto result =
        service()
            .createInventoryItem(
                userId, ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    ArgumentCaptor<InventoryItem> itemCaptor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).saveAndFlush(itemCaptor.capture());
    InventoryItem saved = itemCaptor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getName()).isEqualTo("Cheddar");
    assertThat(saved.getStorageLocation()).isEqualTo(StorageLocation.FRIDGE);
    assertThat(saved.getTrackingMode()).isEqualTo(TrackingMode.QUANTITY);
    assertThat(saved.getQuantity()).isEqualByComparingTo(new BigDecimal("250.000"));
    assertThat(saved.getUnit()).isEqualTo("g");
    assertThat(saved.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);
    assertThat(saved.getSource()).isEqualTo(ItemSource.MANUAL_ADD);

    ArgumentCaptor<InventoryAuditLog> auditCaptor =
        ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    InventoryAuditLog audit = auditCaptor.getValue();
    assertThat(audit.getInventoryItemId()).isEqualTo(saved.getId());
    assertThat(audit.getUserId()).isEqualTo(userId);
    assertThat(audit.getActor()).isEqualTo(AuditActor.USER);
    assertThat(audit.getActorUserId()).isEqualTo(userId);
    assertThat(audit.getFieldChanged()).isEqualTo("created");
    assertThat(audit.getOccurredAt()).isEqualTo(Instant.parse("2026-05-09T10:00:00Z"));
    // toSnapshotJson result must populate the new-value column on the audit row — kills the
    // NullReturnVals mutant on toSnapshotJson at L1326.
    assertThat(audit.getNewValueJson()).isNotNull();
    assertThat(audit.getNewValueJson().isNull()).isFalse();

    ArgumentCaptor<InventoryItemUpsertedEvent> eventCaptor =
        ArgumentCaptor.forClass(InventoryItemUpsertedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    InventoryItemUpsertedEvent event = eventCaptor.getValue();
    assertThat(event.itemId()).isEqualTo(saved.getId());
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.actor()).isEqualTo(AuditActor.USER);
    assertThat(event.scopeKind()).isEqualTo("inventory-item");
    assertThat(event.scopeId()).isEqualTo(saved.getId());
    // currentTraceId() must return a non-null UUID — kills the NullReturnVals mutant on
    // currentTraceId at L1338 (fallback UUID.randomUUID() branch when MDC is empty).
    assertThat(event.traceId()).isNotNull();

    assertThat(result.id()).isEqualTo(saved.getId());
    assertThat(result.name()).isEqualTo("Cheddar");
  }

  @Test
  void createInventoryItem_statusTracked_persistsWithStatus() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .createInventoryItem(
            userId, ProvisionsTestData.createStatusTrackedRequest(), AuditActor.USER);

    ArgumentCaptor<InventoryItem> itemCaptor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).saveAndFlush(itemCaptor.capture());
    InventoryItem saved = itemCaptor.getValue();
    assertThat(saved.getTrackingMode()).isEqualTo(TrackingMode.STATUS);
    assertThat(saved.getStorageLocation()).isEqualTo(StorageLocation.SPICE_RACK);
    assertThat(saved.getStatus()).isEqualTo(StapleStatus.STOCKED);
    assertThat(saved.isStaple()).isTrue();
    assertThat(saved.getQuantity()).isNull();
    assertThat(saved.getUnit()).isNull();
  }

  @Test
  void createInventoryItem_freezerItem_carriesFreezerExtension() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryItemDto result =
        service()
            .createInventoryItem(
                userId, ProvisionsTestData.createFreezerRequest(), AuditActor.USER);

    assertThat(result.storageLocation()).isEqualTo(StorageLocation.FREEZER);
    assertThat(result.freezerExtension()).isNotNull();
    assertThat(result.freezerExtension().maxFreezeWeeks()).isEqualTo(12);
  }

  // ---------------- updateInventoryItem ----------------

  @Test
  void updateInventoryItem_whenItemMissing_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .updateInventoryItem(
                        itemId, userId, ProvisionsTestData.updateQuantityTrackedRequest(0L)))
        .isInstanceOf(InventoryItemNotFoundException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateInventoryItem_whenVersionMismatch_throws409() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(7);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    assertThatThrownBy(
            () ->
                service()
                    .updateInventoryItem(
                        item.getId(), userId, ProvisionsTestData.updateQuantityTrackedRequest(2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void updateInventoryItem_whenChanged_writesOneAuditRowPerChangedField_andPublishesEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Mature Cheddar", // name changed
            "dairy",
            StorageLocation.FRIDGE,
            TrackingMode.QUANTITY,
            new BigDecimal("300.000"), // quantity changed
            "g",
            new BigDecimal("3.49"),
            null,
            false,
            null,
            null,
            "now low fat", // notes changed
            ItemSource.MANUAL_ADD,
            null,
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    // 3 changed fields → 3 audit rows
    verify(auditLogRepository, times(3)).save(any(InventoryAuditLog.class));
    verify(inventoryItemRepository).saveAndFlush(any(InventoryItem.class));
    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
  }

  @Test
  void updateInventoryItem_whenNoOp_writesNoAuditRow_andDoesNotPublishEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .expiryDate(java.time.LocalDate.parse("2026-06-01"))
            .ingredientMappingKey("cheese:cheddar")
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    UpdateInventoryItemRequest sameValues = ProvisionsTestData.updateQuantityTrackedRequest(0L);
    service().updateInventoryItem(item.getId(), userId, sameValues);

    verifyNoInteractions(auditLogRepository);
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- adjustStatus (PATCH /inventory/{itemId}/status) ----------------

  @Test
  void adjustStatus_whenItemMissing_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .adjustStatus(
                        itemId, userId, new AdjustInventoryStatusRequest(StapleStatus.LOW, 0L)))
        .isInstanceOf(InventoryItemNotFoundException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustStatus_whenVersionMismatch_throws409_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build();
    item.setVersion(7);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    assertThatThrownBy(
            () ->
                service()
                    .adjustStatus(
                        item.getId(),
                        userId,
                        new AdjustInventoryStatusRequest(StapleStatus.LOW, 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustStatus_onQuantityTrackedItem_throws400_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    assertThatThrownBy(
            () ->
                service()
                    .adjustStatus(
                        item.getId(),
                        userId,
                        new AdjustInventoryStatusRequest(StapleStatus.OUT, 0L)))
        .isInstanceOf(InvalidInventoryQuantityException.class);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustStatus_whenChanged_setsStatus_writesStatusAuditRow_publishesUpserted() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build(); // STOCKED, staple
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryItemDto result =
        service()
            .adjustStatus(
                item.getId(), userId, new AdjustInventoryStatusRequest(StapleStatus.LOW, 0L));

    assertThat(result.status()).isEqualTo(StapleStatus.LOW);

    ArgumentCaptor<InventoryItem> itemCaptor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).saveAndFlush(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getStatus()).isEqualTo(StapleStatus.LOW);

    ArgumentCaptor<InventoryAuditLog> auditCaptor =
        ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    InventoryAuditLog audit = auditCaptor.getValue();
    assertThat(audit.getInventoryItemId()).isEqualTo(item.getId());
    assertThat(audit.getActor()).isEqualTo(AuditActor.USER);
    assertThat(audit.getActorUserId()).isEqualTo(userId);
    assertThat(audit.getFieldChanged()).isEqualTo("status");
    assertThat(audit.getPreviousValueJson().get("status").asText()).isEqualTo("STOCKED");
    assertThat(audit.getNewValueJson().get("status").asText()).isEqualTo("LOW");

    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
    // LOW is not OUT — the replenishment event must not fire.
    verify(eventPublisher, never()).publishEvent(any(ItemRanOutEvent.class));
  }

  @Test
  void adjustStatus_whenNoOp_writesNothing_andDoesNotBumpVersion() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build(); // STOCKED
    item.setVersion(3);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    InventoryItemDto result =
        service()
            .adjustStatus(
                item.getId(), userId, new AdjustInventoryStatusRequest(StapleStatus.STOCKED, 3L));

    assertThat(result.status()).isEqualTo(StapleStatus.STOCKED);
    assertThat(result.version()).isEqualTo(3L);
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void adjustStatus_stapleTransitionToOut_publishesItemRanOutEvent_once() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.statusTrackedItem(userId)
            .status(StapleStatus.LOW)
            .ingredientMappingKey("salt")
            .build();
    item.setVersion(1);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .adjustStatus(item.getId(), userId, new AdjustInventoryStatusRequest(StapleStatus.OUT, 1L));

    // Exactly two publishes: the generic upsert + exactly ONE ItemRanOutEvent.
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
    List<ItemRanOutEvent> ranOut =
        eventCaptor.getAllValues().stream()
            .filter(ItemRanOutEvent.class::isInstance)
            .map(ItemRanOutEvent.class::cast)
            .toList();
    assertThat(ranOut).hasSize(1);
    ItemRanOutEvent event = ranOut.get(0);
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.affectedItemIds()).containsExactly(item.getId());
    assertThat(event.ingredientMappingKey()).isEqualTo("salt");
    assertThat(event.wasStaple()).isTrue();
  }

  @Test
  void adjustStatus_nonStapleTransitionToOut_doesNotPublishRanOut() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.statusTrackedItem(userId)
            .isStaple(false)
            .status(StapleStatus.LOW)
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .adjustStatus(item.getId(), userId, new AdjustInventoryStatusRequest(StapleStatus.OUT, 0L));

    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
    verify(eventPublisher, never()).publishEvent(any(ItemRanOutEvent.class));
  }

  @Test
  void updateInventoryItem_stapleStatusToOut_publishesItemRanOutEvent_once_parityWithPatch() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.statusTrackedItem(userId).ingredientMappingKey("salt").build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Salt",
            "seasoning",
            StorageLocation.SPICE_RACK,
            TrackingMode.STATUS,
            null,
            null,
            null,
            StapleStatus.OUT, // STOCKED -> OUT via full PUT
            true,
            null,
            "salt",
            null,
            ItemSource.MANUAL_ADD,
            null,
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    // Exactly two publishes: the generic upsert + exactly ONE ItemRanOutEvent (parity with PATCH).
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
    List<ItemRanOutEvent> ranOut =
        eventCaptor.getAllValues().stream()
            .filter(ItemRanOutEvent.class::isInstance)
            .map(ItemRanOutEvent.class::cast)
            .toList();
    assertThat(ranOut).hasSize(1);
    ItemRanOutEvent event = ranOut.get(0);
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.affectedItemIds()).containsExactly(item.getId());
    assertThat(event.ingredientMappingKey()).isEqualTo("salt");
    assertThat(event.wasStaple()).isTrue();
    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
  }

  @Test
  void updateInventoryItem_stapleAlreadyOut_doesNotRepublishRanOut() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.statusTrackedItem(userId).status(StapleStatus.OUT).build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Sea Salt", // name changed so the PUT is not a no-op
            "seasoning",
            StorageLocation.SPICE_RACK,
            TrackingMode.STATUS,
            null,
            null,
            null,
            StapleStatus.OUT, // OUT -> OUT: no transition
            true,
            null,
            null,
            null,
            ItemSource.MANUAL_ADD,
            null,
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    verify(eventPublisher).publishEvent(any(InventoryItemUpsertedEvent.class));
    verify(eventPublisher, never()).publishEvent(any(ItemRanOutEvent.class));
  }

  // ---------------- listActiveInventory filters ----------------

  private static final Pageable PAGE = PageRequest.of(0, 20);

  @Test
  void listActiveInventory_nullItemStatus_defaultsToActive_withNoExpiryBound() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, null, PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    service().listActiveInventory(userId, InventorySearchCriteria.none(), PAGE);

    verify(inventoryItemRepository)
        .findForUser(userId, ItemLifecycleStatus.ACTIVE, null, null, null, PAGE);
  }

  @Test
  void listActiveInventory_passesItemStatusThrough() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findForUser(
            userId, ItemLifecycleStatus.SPOILED, null, null, null, PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    service()
        .listActiveInventory(
            userId,
            new InventorySearchCriteria(null, null, ItemLifecycleStatus.SPOILED, null),
            PAGE);

    verify(inventoryItemRepository)
        .findForUser(userId, ItemLifecycleStatus.SPOILED, null, null, null, PAGE);
  }

  @Test
  void listActiveInventory_computesExpiryBoundAsTodayPlusDays_fromClock() {
    // fixedClock = 2026-05-09T10:00:00Z (UTC) -> today + 7 = 2026-05-16
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, LocalDate.parse("2026-05-16"), PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    service().listActiveInventory(userId, new InventorySearchCriteria(null, null, null, 7), PAGE);

    verify(inventoryItemRepository)
        .findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, LocalDate.parse("2026-05-16"), PAGE);
  }

  @Test
  void listActiveInventory_expiringWithinZeroDays_boundIsToday() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, LocalDate.parse("2026-05-09"), PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    service().listActiveInventory(userId, new InventorySearchCriteria(null, null, null, 0), PAGE);

    verify(inventoryItemRepository)
        .findForUser(
            userId, ItemLifecycleStatus.ACTIVE, null, null, LocalDate.parse("2026-05-09"), PAGE);
  }

  @Test
  void listActiveInventory_composesAllFourFilters() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findForUser(
            userId,
            ItemLifecycleStatus.EXHAUSTED,
            StorageLocation.FRIDGE,
            Boolean.TRUE,
            LocalDate.parse("2026-05-12"),
            PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    service()
        .listActiveInventory(
            userId,
            new InventorySearchCriteria(
                StorageLocation.FRIDGE, true, ItemLifecycleStatus.EXHAUSTED, 3),
            PAGE);

    verify(inventoryItemRepository)
        .findForUser(
            userId,
            ItemLifecycleStatus.EXHAUSTED,
            StorageLocation.FRIDGE,
            Boolean.TRUE,
            LocalDate.parse("2026-05-12"),
            PAGE);
  }

  // ---------------- upsertEquipment ----------------

  @Test
  void upsertEquipment_whenNotPresent_createsAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.empty());
    when(equipmentRepository.saveAndFlush(any(Equipment.class)))
        .thenAnswer(
            inv -> {
              Equipment captured = inv.getArgument(0);
              captured.setVersion(0);
              return captured;
            });

    ProvisionUpdateService.UpsertResult<EquipmentDto> result =
        service()
            .upsertEquipment(userId, "oven", ProvisionsTestData.upsertEquipmentRequestForCreate());

    assertThat(result.created()).isTrue();
    assertThat(result.value().name()).isEqualTo("oven");
    assertThat(result.value().available()).isTrue();
    verify(equipmentRepository).saveAndFlush(any(Equipment.class));
    verify(eventPublisher).publishEvent(any(EquipmentChangedEvent.class));
  }

  @Test
  void upsertEquipment_whenPresentAndVersionMatches_updates() {
    UUID userId = UUID.randomUUID();
    Equipment existing =
        ProvisionsTestData.equipment(userId, "oven").available(false).version(2L).build();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.of(existing));
    when(equipmentRepository.saveAndFlush(any(Equipment.class)))
        .thenAnswer(
            inv -> {
              Equipment captured = inv.getArgument(0);
              captured.setVersion(3L);
              return captured;
            });

    ProvisionUpdateService.UpsertResult<EquipmentDto> result =
        service()
            .upsertEquipment(
                userId,
                "oven",
                ProvisionsTestData.upsertEquipmentRequest(true, "now repaired", 2L));

    assertThat(result.created()).isFalse();
    assertThat(result.value().available()).isTrue();
    assertThat(result.value().version()).isEqualTo(3L);
    verify(eventPublisher).publishEvent(any(EquipmentChangedEvent.class));
  }

  @Test
  void upsertEquipment_whenStaleExpectedVersion_throws409() {
    UUID userId = UUID.randomUUID();
    Equipment existing = ProvisionsTestData.equipment(userId, "oven").version(5L).build();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .upsertEquipment(
                        userId, "oven", ProvisionsTestData.upsertEquipmentRequest(true, null, 0L)))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

    verify(equipmentRepository, never()).saveAndFlush(any(Equipment.class));
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void upsertEquipment_whenPresentAndExpectedVersionNull_throws409() {
    UUID userId = UUID.randomUUID();
    Equipment existing = ProvisionsTestData.equipment(userId, "oven").version(0L).build();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .upsertEquipment(
                        userId,
                        "oven",
                        ProvisionsTestData.upsertEquipmentRequest(true, null, null)))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
  }

  // ---------------- deleteEquipment ----------------

  @Test
  void deleteEquipment_whenPresent_deletesAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    Equipment existing = ProvisionsTestData.equipment(userId, "oven").build();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.of(existing));

    service().deleteEquipment(userId, "oven");

    verify(equipmentRepository).delete(existing);
    ArgumentCaptor<EquipmentChangedEvent> captor =
        ArgumentCaptor.forClass(EquipmentChangedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().nowAvailable()).isFalse();
    assertThat(captor.getValue().equipmentName()).isEqualTo("oven");
  }

  @Test
  void deleteEquipment_whenMissing_throws404() {
    UUID userId = UUID.randomUUID();
    when(equipmentRepository.findByUserIdAndName(userId, "ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().deleteEquipment(userId, "ghost"))
        .isInstanceOf(EquipmentNotFoundException.class);

    verifyNoInteractions(eventPublisher);
  }

  // ---------------- markSpoiled ----------------

  @Test
  void markSpoiled_whenActive_setsStatusWritesAuditAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0L);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryItemDto result = service().markSpoiled(item.getId(), userId);

    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.SPOILED);
    verify(auditLogRepository).save(any(InventoryAuditLog.class));
    verify(eventPublisher).publishEvent(any(ItemSpoiledEvent.class));
  }

  @Test
  void markSpoiled_whenAlreadySpoiled_isIdempotent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.SPOILED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    service().markSpoiled(item.getId(), userId);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void markSpoiled_whenNotOwned_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().markSpoiled(itemId, userId))
        .isInstanceOf(InventoryItemNotFoundException.class);
  }

  // ---------------- markExhausted ----------------

  @Test
  void markExhausted_whenActive_publishesItemRanOutEventWithStapleFlag() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .isStaple(true)
            .ingredientMappingKey("cheese:cheddar")
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().markExhausted(item.getId(), userId);

    ArgumentCaptor<ItemRanOutEvent> captor = ArgumentCaptor.forClass(ItemRanOutEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().wasStaple()).isTrue();
    assertThat(captor.getValue().ingredientMappingKey()).isEqualTo("cheese:cheddar");
    verify(auditLogRepository).save(any(InventoryAuditLog.class));
  }

  @Test
  void markExhausted_whenAlreadyExhausted_isIdempotent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.EXHAUSTED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    service().markExhausted(item.getId(), userId);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- reverseGroceryLineAdd (grocery-undo-pantry-reversal) ----------------

  @Test
  void reverseGroceryLineAdd_fullDecrementToZero_marksExhausted_writesAudit_publishesEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId).quantity(new BigDecimal("2.000")).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("2.000"), "g", userId);

    assertThat(result).isPresent();
    assertThat(item.getQuantity()).isEqualByComparingTo("0");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getActor()).isEqualTo(AuditActor.GROCERY_IMPORT);
    assertThat(audit.getValue().getActorUserId()).isEqualTo(userId);
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("reversed");

    ArgumentCaptor<com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent> evt =
        ArgumentCaptor.forClass(
            com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().source())
        .isEqualTo(com.example.mealprep.provisions.event.ItemAdjustmentSource.GROCERY_IMPORT);
    assertThat(evt.getValue().affectedItemIds()).containsExactly(item.getId());
  }

  @Test
  void reverseGroceryLineAdd_partialRemainder_decrementsExactly_staysActive() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId).quantity(new BigDecimal("5.000")).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("2.000"), "g", userId);

    assertThat(result).isPresent();
    assertThat(item.getQuantity()).isEqualByComparingTo("3.000");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);
  }

  @Test
  void reverseGroceryLineAdd_partiallyConsumedSince_flooredAtZero_auditSaysFloored() {
    UUID userId = UUID.randomUUID();
    // Only 1.0 left of an added 2.0 (a cook event consumed the rest) → floor at zero, never -1.
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId).quantity(new BigDecimal("1.000")).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("2.000"), "g", userId);

    assertThat(result).isPresent();
    assertThat(item.getQuantity()).isEqualByComparingTo("0");
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("floored_at_zero");
  }

  @Test
  void reverseGroceryLineAdd_itemMissing_isSilentNoop() {
    UUID userId = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(missing, userId)).thenReturn(Optional.empty());

    var result = service().reverseGroceryLineAdd(missing, new BigDecimal("1.000"), "g", userId);

    assertThat(result).isEmpty();
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void reverseGroceryLineAdd_itemSpoiledSince_noop_butAudited() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.SPOILED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("1.000"), "g", userId);

    assertThat(result).isEmpty();
    assertThat(item.getQuantity()).isEqualByComparingTo("250.000"); // untouched
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));

    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getActor()).isEqualTo(AuditActor.GROCERY_IMPORT);
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("skipped_item_spoiled");
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void reverseGroceryLineAdd_statusTrackedItem_noop_butAudited() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.statusTrackedItem(userId).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("1.000"), "items", userId);

    assertThat(result).isEmpty();
    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("skipped_status_tracked");
  }

  @Test
  void reverseGroceryLineAdd_unitMismatch_noop_butAudited() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build(); // unit "g"
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    var result =
        service().reverseGroceryLineAdd(item.getId(), new BigDecimal("1.000"), "kg", userId);

    assertThat(result).isEmpty();
    assertThat(item.getQuantity()).isEqualByComparingTo("250.000"); // no blind cross-unit maths
    ArgumentCaptor<InventoryAuditLog> audit = ArgumentCaptor.forClass(InventoryAuditLog.class);
    verify(auditLogRepository).save(audit.capture());
    assertThat(audit.getValue().getNewValueJson().get("groceryLineReversal").asText())
        .isEqualTo("skipped_unit_mismatch");
  }

  // ---------------- softDeleteInventoryItem ----------------

  @Test
  void softDeleteInventoryItem_whenActive_setsWastedWritesAuditNoEvent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().softDeleteInventoryItem(item.getId(), userId);

    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.WASTED);
    verify(auditLogRepository).save(any(InventoryAuditLog.class));
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void softDeleteInventoryItem_whenAlreadyWasted_isIdempotent() {
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.WASTED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    service().softDeleteInventoryItem(item.getId(), userId);

    verify(inventoryItemRepository, never()).saveAndFlush(any(InventoryItem.class));
    verifyNoInteractions(auditLogRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void softDeleteInventoryItem_whenNotOwned_throws404() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    when(inventoryItemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().softDeleteInventoryItem(itemId, userId))
        .isInstanceOf(InventoryItemNotFoundException.class);
  }

  @Test
  void markSpoiled_returnsDtoReflectingSpoiledStatus_notNull() {
    // Kill the NullReturnVals mutant on the final mapper.toDto(saved) in markSpoiled.
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0L);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryItemDto result = service().markSpoiled(item.getId(), userId);

    assertThat(result).isNotNull();
    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.SPOILED);
    assertThat(result.id()).isEqualTo(item.getId());
  }

  @Test
  void markSpoiled_whenAlreadySpoiled_returnsDtoNotNull() {
    // Kill NullReturnVals on the idempotent early-return mapper.toDto(item).
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.SPOILED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    InventoryItemDto result = service().markSpoiled(item.getId(), userId);

    assertThat(result).isNotNull();
    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.SPOILED);
  }

  @Test
  void markExhausted_returnsDtoReflectingExhaustedStatus_notNull() {
    // Kill NullReturnVals on the final return mapper.toDto(saved) in markExhausted.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .isStaple(true)
            .ingredientMappingKey("cheese:cheddar")
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    InventoryItemDto result = service().markExhausted(item.getId(), userId);

    assertThat(result).isNotNull();
    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    // Also kills the VoidMethodCall on `item.setItemStatus(EXHAUSTED)`.
    assertThat(item.getItemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
  }

  @Test
  void markExhausted_whenAlreadyExhausted_returnsDtoNotNull() {
    // Kill NullReturnVals on the idempotent early-return.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .itemStatus(ItemLifecycleStatus.EXHAUSTED)
            .build();
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    InventoryItemDto result = service().markExhausted(item.getId(), userId);

    assertThat(result).isNotNull();
    assertThat(result.itemStatus()).isEqualTo(ItemLifecycleStatus.EXHAUSTED);
  }

  @Test
  void updateInventoryItem_whenNoOp_returnsDtoNotNull() {
    // Kill NullReturnVals on the no-op early-return `return mapper.toDto(item)`.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .expiryDate(java.time.LocalDate.parse("2026-06-01"))
            .ingredientMappingKey("cheese:cheddar")
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));

    InventoryItemDto result =
        service()
            .updateInventoryItem(
                item.getId(), userId, ProvisionsTestData.updateQuantityTrackedRequest(0L));

    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(item.getId());
  }

  @Test
  void updateInventoryItem_whenChanged_returnsDtoNotNull() {
    // Kill NullReturnVals on the post-save `return mapper.toDto(saved)`.
    UUID userId = UUID.randomUUID();
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Mature Cheddar",
            "dairy",
            StorageLocation.FRIDGE,
            TrackingMode.QUANTITY,
            new BigDecimal("250.000"),
            "g",
            new BigDecimal("3.49"),
            null,
            false,
            null,
            null,
            null,
            ItemSource.MANUAL_ADD,
            null,
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    InventoryItemDto result = service().updateInventoryItem(item.getId(), userId, request);

    assertThat(result).isNotNull();
    assertThat(result.name()).isEqualTo("Mature Cheddar");
  }

  @Test
  void updateInventoryItem_changesTrackingMode_andStatus_andItemStatus() {
    // Kill the VoidMethodCall mutants on setTrackingMode (L488), setStatus (L492), setItemStatus
    // (L499) — earlier tests didn't toggle any of these.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .trackingMode(TrackingMode.QUANTITY)
            .status(null)
            .itemStatus(ItemLifecycleStatus.ACTIVE)
            .storageLocation(StorageLocation.SPICE_RACK)
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Salt",
            "spice",
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
            ItemLifecycleStatus.SPOILED,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).saveAndFlush(captor.capture());
    InventoryItem saved = captor.getValue();
    assertThat(saved.getTrackingMode()).isEqualTo(TrackingMode.STATUS);
    assertThat(saved.getStatus()).isEqualTo(StapleStatus.STOCKED);
    assertThat(saved.getItemStatus()).isEqualTo(ItemLifecycleStatus.SPOILED);
  }

  @Test
  void updateInventoryItem_freezerExtension_setsAllFields_clearsThemWhenNull() {
    // Kill VoidMethodCall on the applyFreezerExtension call site (L500) and the 9 setters inside
    // it (L1287-1298). Two scenarios — populate then clear — observed on the captured entity.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.freezerItem(userId)
            .frozenAt(java.time.LocalDate.parse("2026-04-01"))
            .maxFreezeWeeks(12)
            .defrostMethod(com.example.mealprep.provisions.domain.entity.DefrostMethod.MICROWAVE)
            .defrostLeadTimeHours(2)
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Scenario 1 — replace extension with a different one. Every setter on the populated path
    // must run; an un-mutated setter would leave the old values in place.
    UUID recipeId = UUID.randomUUID();
    com.example.mealprep.provisions.api.dto.FreezerExtensionDto newExt =
        new com.example.mealprep.provisions.api.dto.FreezerExtensionDto(
            java.time.LocalDate.parse("2026-04-10"),
            8,
            com.example.mealprep.provisions.domain.entity.DefrostMethod.OVERNIGHT_FRIDGE,
            6,
            recipeId);
    UpdateInventoryItemRequest replace =
        new UpdateInventoryItemRequest(
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
            ItemLifecycleStatus.ACTIVE,
            newExt,
            0L);

    service().updateInventoryItem(item.getId(), userId, replace);

    assertThat(item.getFrozenAt()).isEqualTo(java.time.LocalDate.parse("2026-04-10"));
    assertThat(item.getMaxFreezeWeeks()).isEqualTo(8);
    assertThat(item.getDefrostMethod())
        .isEqualTo(com.example.mealprep.provisions.domain.entity.DefrostMethod.OVERNIGHT_FRIDGE);
    assertThat(item.getDefrostLeadTimeHours()).isEqualTo(6);
    assertThat(item.getSourceRecipeId()).isEqualTo(recipeId);
  }

  @Test
  void updateInventoryItem_freezerExtension_clearsAllFields_whenNullDto() {
    // Kill VoidMethodCall mutants on the null-DTO branch of applyFreezerExtension (L1287-1291).
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.freezerItem(userId)
            .frozenAt(java.time.LocalDate.parse("2026-04-01"))
            .maxFreezeWeeks(12)
            .defrostMethod(com.example.mealprep.provisions.domain.entity.DefrostMethod.MICROWAVE)
            .defrostLeadTimeHours(2)
            .sourceRecipeId(UUID.randomUUID())
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest clearAll =
        new UpdateInventoryItemRequest(
            "Frozen Peas",
            "vegetable",
            StorageLocation.CUPBOARD, // moved out of freezer
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
            ItemLifecycleStatus.ACTIVE,
            null, // freezerExtension cleared
            0L);

    service().updateInventoryItem(item.getId(), userId, clearAll);

    assertThat(item.getFrozenAt()).isNull();
    assertThat(item.getMaxFreezeWeeks()).isNull();
    assertThat(item.getDefrostMethod()).isNull();
    assertThat(item.getDefrostLeadTimeHours()).isNull();
    assertThat(item.getSourceRecipeId()).isNull();
  }

  @Test
  void updateInventoryItem_replacesEveryFieldOnTheEntity() {
    // Kill the VoidMethodCall mutants on each item.setXxx(...) in updateInventoryItem (L485-499)
    // by changing EVERY field and asserting against the captured entity.
    UUID userId = UUID.randomUUID();
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name("Original Name")
            .category("dairy")
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .costPaid(new BigDecimal("1.00"))
            .isStaple(false)
            .expiryDate(null)
            .ingredientMappingKey("cheese:cheddar")
            .notes(null)
            .sourceRef(null)
            .itemStatus(ItemLifecycleStatus.ACTIVE)
            .build();
    item.setVersion(0);
    when(inventoryItemRepository.findByIdAndUserId(item.getId(), userId))
        .thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateInventoryItemRequest request =
        new UpdateInventoryItemRequest(
            "Mature Cheddar",
            "dairy-aged",
            StorageLocation.CUPBOARD,
            TrackingMode.QUANTITY,
            new BigDecimal("250.000"),
            "kg",
            new BigDecimal("4.99"),
            null,
            true,
            java.time.LocalDate.parse("2026-06-15"),
            "cheese:cheddar-mature",
            "stored at the front",
            ItemSource.OTHER_SHOP,
            "ord-9",
            ItemLifecycleStatus.ACTIVE,
            null,
            0L);

    service().updateInventoryItem(item.getId(), userId, request);

    ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).saveAndFlush(captor.capture());
    InventoryItem saved = captor.getValue();
    // Every setter must have run — if any VoidMethodCall mutant suppresses it, the field is
    // observably unchanged.
    assertThat(saved.getName()).isEqualTo("Mature Cheddar");
    assertThat(saved.getCategory()).isEqualTo("dairy-aged");
    assertThat(saved.getStorageLocation()).isEqualTo(StorageLocation.CUPBOARD);
    assertThat(saved.getTrackingMode()).isEqualTo(TrackingMode.QUANTITY);
    assertThat(saved.getQuantity()).isEqualByComparingTo("250.000");
    assertThat(saved.getUnit()).isEqualTo("kg");
    assertThat(saved.getCostPaid()).isEqualByComparingTo("4.99");
    assertThat(saved.isStaple()).isTrue();
    assertThat(saved.getExpiryDate()).isEqualTo(java.time.LocalDate.parse("2026-06-15"));
    assertThat(saved.getIngredientMappingKey()).isEqualTo("cheese:cheddar-mature");
    assertThat(saved.getNotes()).isEqualTo("stored at the front");
    assertThat(saved.getSource()).isEqualTo(ItemSource.OTHER_SHOP);
    assertThat(saved.getSourceRef()).isEqualTo("ord-9");
    assertThat(saved.getItemStatus()).isEqualTo(ItemLifecycleStatus.ACTIVE);
  }

  @Test
  void upsertEquipment_updatePath_setsDetailsOnExistingRow() {
    // Kill the VoidMethodCall mutant at L543: existing.setDetails(request.details()).
    // The existing upsertEquipment_whenPresentAndVersionMatches_updates test only asserts on
    // available; assert on details too.
    UUID userId = UUID.randomUUID();
    Equipment existing =
        ProvisionsTestData.equipment(userId, "oven")
            .available(true)
            .details("old details")
            .version(0L)
            .build();
    when(equipmentRepository.findByUserIdAndName(userId, "oven")).thenReturn(Optional.of(existing));
    when(equipmentRepository.saveAndFlush(any(Equipment.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .upsertEquipment(
            userId, "oven", ProvisionsTestData.upsertEquipmentRequest(false, "new details", 0L));

    // Field on the existing entity must have been mutated by the setter.
    assertThat(existing.getDetails()).isEqualTo("new details");
    assertThat(existing.isAvailable()).isFalse();
  }

  @Test
  void createInventoryItem_invalidQuantity_isNotCalledHere() {
    // The @PrePersist hook only fires on flush; service-level validation is delegated to
    // Jakarta + the @PrePersist callback. This test documents that the service does NOT
    // pre-validate quantity (the hook + @ValidQuantity do).
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CreateInventoryItemRequest request =
        new CreateInventoryItemRequest(
            "Cheddar",
            "dairy",
            StorageLocation.FRIDGE,
            TrackingMode.QUANTITY,
            new BigDecimal("0.000"),
            "g",
            null,
            null,
            false,
            null,
            null,
            null,
            ItemSource.MANUAL_ADD,
            null,
            null);

    InventoryItemDto dto = service().createInventoryItem(userId, request, AuditActor.USER);
    assertThat(dto.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ---------------- notification/01b scanner reads ----------------

  @Test
  void getUserIdsWithActiveInventory_delegatesToRepo() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    when(inventoryItemRepository.findDistinctUserIdsByItemStatus(ItemLifecycleStatus.ACTIVE))
        .thenReturn(java.util.List.of(u1, u2));

    assertThat(service().getUserIdsWithActiveInventory()).containsExactly(u1, u2);
  }

  @Test
  void getExpiringInventory_mapsToDto() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate max = java.time.LocalDate.of(2026, 6, 30);
    InventoryItem item = ProvisionsTestData.quantityTrackedItem(userId).build();
    when(inventoryItemRepository.findActiveExpiringForUser(userId, max))
        .thenReturn(java.util.List.of(item));

    assertThat(service().getExpiringInventory(userId, max))
        .extracting(InventoryItemDto::id)
        .containsExactly(item.getId());
  }

  @Test
  void getDefrostCandidates_mapsToDto() {
    UUID userId = UUID.randomUUID();
    InventoryItem frozen = ProvisionsTestData.freezerItem(userId).build();
    when(inventoryItemRepository.findActiveDefrostCandidatesForUser(userId))
        .thenReturn(java.util.List.of(frozen));

    java.util.List<InventoryItemDto> result = service().getDefrostCandidates(userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).freezerExtension().defrostLeadTimeHours()).isEqualTo(8);
  }

  @Test
  void getStaplesNeedingReplenishment_queriesLowAndOut() {
    UUID userId = UUID.randomUUID();
    InventoryItem staple =
        ProvisionsTestData.statusTrackedItem(userId)
            .isStaple(true)
            .status(StapleStatus.LOW)
            .build();
    when(inventoryItemRepository.findActiveStaplesForUserByStatusIn(
            userId, java.util.List.of(StapleStatus.LOW, StapleStatus.OUT)))
        .thenReturn(java.util.List.of(staple));

    assertThat(service().getStaplesNeedingReplenishment(userId))
        .extracting(InventoryItemDto::id)
        .containsExactly(staple.getId());
  }

  @Test
  void getActiveInventoryByMappingKey_mapsRepoRowsToDtoPreservingOrder() {
    UUID userId = UUID.randomUUID();
    InventoryItem older =
        ProvisionsTestData.quantityTrackedItem(userId)
            .ingredientMappingKey("soy_sauce")
            .expiryDate(java.time.LocalDate.of(2026, 6, 1))
            .build();
    InventoryItem newer =
        ProvisionsTestData.quantityTrackedItem(userId)
            .ingredientMappingKey("soy_sauce")
            .expiryDate(java.time.LocalDate.of(2026, 7, 1))
            .build();
    // Repo returns oldest-expiry first (NULLS LAST) — the service must preserve that order.
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "soy_sauce"))
        .thenReturn(java.util.List.of(older, newer));

    java.util.List<InventoryItemDto> result =
        service().getActiveInventoryByMappingKey(userId, "soy_sauce");

    assertThat(result)
        .extracting(InventoryItemDto::id)
        .containsExactly(older.getId(), newer.getId());
  }

  @Test
  void getActiveInventoryByMappingKey_noRows_returnsEmptyList() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, "soy_sauce"))
        .thenReturn(java.util.List.of());

    assertThat(service().getActiveInventoryByMappingKey(userId, "soy_sauce")).isEmpty();
  }

  // ---------------- pantry_tracking_enabled gating (provisions-2) ----------------

  private void stubPantryTracking(UUID userId, boolean enabled) {
    var document =
        new com.example.mealprep.preference.domain.document.LifestyleConfigDocument(
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
            new com.example.mealprep.preference.domain.document.LifestyleConfigDocument
                .PantryTracking(enabled));
    var dto =
        new com.example.mealprep.preference.api.dto.LifestyleConfigDto(
            UUID.randomUUID(),
            userId,
            document,
            null,
            0L,
            java.time.Instant.parse("2026-05-01T00:00:00Z"),
            java.time.Instant.parse("2026-05-01T00:00:00Z"));
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.of(dto));
  }

  @Test
  void getStaplesNeedingReplenishment_pantryDisabled_returnsEmpty_withoutQueryingRepo() {
    UUID userId = UUID.randomUUID();
    stubPantryTracking(userId, false);

    assertThat(service().getStaplesNeedingReplenishment(userId)).isEmpty();
    verify(inventoryItemRepository, never()).findActiveStaplesForUserByStatusIn(any(), any());
  }

  @Test
  void getStaplesNeedingReplenishment_pantryEnabled_queriesRepo() {
    UUID userId = UUID.randomUUID();
    stubPantryTracking(userId, true);
    when(inventoryItemRepository.findActiveStaplesForUserByStatusIn(eq(userId), any()))
        .thenReturn(java.util.List.of());

    service().getStaplesNeedingReplenishment(userId);
    verify(inventoryItemRepository).findActiveStaplesForUserByStatusIn(eq(userId), any());
  }

  @Test
  void getStaplesNeedingReplenishment_noLifestyleConfig_defaultsToEnabled() {
    UUID userId = UUID.randomUUID();
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());
    when(inventoryItemRepository.findActiveStaplesForUserByStatusIn(eq(userId), any()))
        .thenReturn(java.util.List.of());

    service().getStaplesNeedingReplenishment(userId);
    // Unset flag → tracking ENABLED (non-breaking default) → repo IS queried.
    verify(inventoryItemRepository).findActiveStaplesForUserByStatusIn(eq(userId), any());
  }

  @Test
  void applyCookEvent_pantryDisabled_isNoOp_returnsEmptyResult() {
    UUID userId = UUID.randomUUID();
    stubPantryTracking(userId, false);
    UUID mealSlotId = UUID.randomUUID();
    var cmd =
        new com.example.mealprep.provisions.api.dto.CookEventCommand(
            UUID.randomUUID(),
            null,
            mealSlotId,
            1,
            false,
            null,
            false,
            "dk",
            java.util.List.of(
                new com.example.mealprep.provisions.api.dto.RecipeIngredientUsage(
                    "cheese:cheddar", new BigDecimal("50"), "g")),
            null,
            null,
            null);

    // Inject a dedupe-repo mock just for this no-op path (the early-return needs it).
    var dedupeRepo =
        org.mockito.Mockito.mock(
            com.example.mealprep.provisions.domain.repository.CookEventDedupeRepository.class);
    when(dedupeRepo.existsByIdMealSlotIdAndIdDedupeKey(mealSlotId, "dk")).thenReturn(false);
    ProvisionServiceImpl svc =
        new ProvisionServiceImpl(
            inventoryItemRepository,
            auditLogRepository,
            equipmentRepository,
            budgetRepository,
            supplierProductRepository,
            wasteEntryRepository,
            dedupeRepo,
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

    var result = svc.applyCookEvent(userId, cmd);
    assertThat(result.updatedItems()).isEmpty();
    assertThat(result.exhaustedItems()).isEmpty();
    assertThat(result.underflows()).isEmpty();
    // No deduction engine touched (it's null) — proves the gate short-circuited before deduction.
    verify(inventoryItemRepository, never()).findActiveByMappingKeyOrderByExpiryAsc(any(), any());
  }
}
