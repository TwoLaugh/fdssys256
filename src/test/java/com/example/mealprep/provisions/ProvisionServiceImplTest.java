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
        mapper,
        equipmentMapper,
        budgetMapper,
        inventoryAuditMapper,
        supplierProductMapper,
        wasteEntryMapper,
        eventPublisher,
        objectMapper,
        fixedClock,
        householdQueryService);
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

    ArgumentCaptor<InventoryItemUpsertedEvent> eventCaptor =
        ArgumentCaptor.forClass(InventoryItemUpsertedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    InventoryItemUpsertedEvent event = eventCaptor.getValue();
    assertThat(event.itemId()).isEqualTo(saved.getId());
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.actor()).isEqualTo(AuditActor.USER);
    assertThat(event.scopeKind()).isEqualTo("inventory-item");
    assertThat(event.scopeId()).isEqualTo(saved.getId());

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
}
