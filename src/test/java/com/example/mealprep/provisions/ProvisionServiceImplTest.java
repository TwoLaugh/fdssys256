package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
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
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
        null);
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
}
