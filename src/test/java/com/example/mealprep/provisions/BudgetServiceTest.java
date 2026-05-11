package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.PriceSensitivity;
import com.example.mealprep.provisions.api.dto.UpdateBudgetRequest;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.domain.entity.Budget;
import com.example.mealprep.provisions.domain.repository.BudgetRepository;
import com.example.mealprep.provisions.domain.repository.EquipmentRepository;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.domain.service.internal.ProvisionServiceImpl;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import com.example.mealprep.provisions.exception.BudgetCurrencyChangeException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Unit-level coverage of the budget upsert / read paths in {@link ProvisionServiceImpl}. The
 * repository, event publisher, and inventory-side dependencies are mocked; the real {@link
 * BudgetMapper} (default-method-only interface) is used directly.
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private EquipmentRepository equipmentRepository;
  @Mock private BudgetRepository budgetRepository;
  @Mock private SupplierProductRepository supplierProductRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final InventoryItemMapper mapper =
      new com.example.mealprep.provisions.api.mapper.InventoryItemMapperImpl();
  private final EquipmentMapper equipmentMapper =
      new com.example.mealprep.provisions.api.mapper.EquipmentMapperImpl();
  private final BudgetMapper budgetMapper = new BudgetMapper() {};
  private final InventoryAuditMapper inventoryAuditMapper =
      new com.example.mealprep.provisions.api.mapper.InventoryAuditMapperImpl();
  private final SupplierProductMapper supplierProductMapper = new SupplierProductMapper() {};

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
        mapper,
        equipmentMapper,
        budgetMapper,
        inventoryAuditMapper,
        supplierProductMapper,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  // ---------------- getBudget ----------------

  @Test
  void getBudget_returnsEmpty_whenRowMissing() {
    UUID userId = UUID.randomUUID();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getBudget(userId)).isEmpty();
  }

  @Test
  void getBudget_returnsDto_withSpendTrackingNull() {
    UUID userId = UUID.randomUUID();
    Budget existing = ProvisionsTestData.budget(userId).build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    Optional<BudgetDto> result = service().getBudget(userId);

    assertThat(result).isPresent();
    assertThat(result.get().userId()).isEqualTo(userId);
    assertThat(result.get().spendTracking()).isNull();
  }

  // ---------------- getBudgetsByUserIds ----------------

  @Test
  void getBudgetsByUserIds_emptyInput_returnsEmptyList_andSkipsRepository() {
    assertThat(service().getBudgetsByUserIds(List.of())).isEmpty();
    verifyNoInteractions(budgetRepository);
  }

  @Test
  void getBudgetsByUserIds_returnsMappedDtos() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Budget budgetA = ProvisionsTestData.budget(a).build();
    Budget budgetB = ProvisionsTestData.budget(b).build();
    when(budgetRepository.findAllByUserIdIn(List.of(a, b))).thenReturn(List.of(budgetA, budgetB));

    List<BudgetDto> result = service().getBudgetsByUserIds(List.of(a, b));

    assertThat(result).hasSize(2);
    assertThat(result).allSatisfy(dto -> assertThat(dto.spendTracking()).isNull());
  }

  // ---------------- upsertBudget — insert path ----------------

  @Test
  void upsertBudget_insertPath_persistsAndPublishesEventWithNullPrevious() {
    UUID userId = UUID.randomUUID();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(budgetRepository.saveAndFlush(any(Budget.class)))
        .thenAnswer(inv -> inv.getArgument(0, Budget.class));

    UpdateBudgetRequest request = ProvisionsTestData.updateBudgetRequestForCreate();

    BudgetDto result = service().upsertBudget(userId, request);

    assertThat(result.weeklyTarget()).isEqualByComparingTo("75.00");
    assertThat(result.currency()).isEqualTo("GBP");
    assertThat(result.priceSensitivity()).isEqualTo(PriceSensitivity.moderate);
    assertThat(result.enabled()).isTrue();
    assertThat(result.spendTracking()).isNull();

    ArgumentCaptor<BudgetChangedEvent> events = ArgumentCaptor.forClass(BudgetChangedEvent.class);
    verify(eventPublisher).publishEvent(events.capture());
    assertThat(events.getValue().previousWeeklyTarget()).isNull();
    assertThat(events.getValue().newWeeklyTarget()).isEqualByComparingTo("75.00");
  }

  // ---------------- upsertBudget — update path ----------------

  @Test
  void upsertBudget_updatePath_versionMatch_updatesAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    Budget existing =
        ProvisionsTestData.budget(userId).weeklyTarget(new BigDecimal("60.00")).version(3L).build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
    when(budgetRepository.saveAndFlush(any(Budget.class)))
        .thenAnswer(inv -> inv.getArgument(0, Budget.class));

    UpdateBudgetRequest request =
        ProvisionsTestData.updateBudgetRequest(
            new BigDecimal("80.00"),
            "GBP",
            new BigDecimal("5.00"),
            PriceSensitivity.high,
            true,
            3L);

    BudgetDto result = service().upsertBudget(userId, request);

    assertThat(result.weeklyTarget()).isEqualByComparingTo("80.00");
    assertThat(result.priceSensitivity()).isEqualTo(PriceSensitivity.high);

    ArgumentCaptor<BudgetChangedEvent> events = ArgumentCaptor.forClass(BudgetChangedEvent.class);
    verify(eventPublisher).publishEvent(events.capture());
    assertThat(events.getValue().previousWeeklyTarget()).isEqualByComparingTo("60.00");
    assertThat(events.getValue().newWeeklyTarget()).isEqualByComparingTo("80.00");
  }

  @Test
  void upsertBudget_updatePath_staleVersion_throws409Equivalent() {
    UUID userId = UUID.randomUUID();
    Budget existing = ProvisionsTestData.budget(userId).version(7L).build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    UpdateBudgetRequest request =
        ProvisionsTestData.updateBudgetRequest(
            new BigDecimal("80.00"),
            "GBP",
            new BigDecimal("5.00"),
            PriceSensitivity.moderate,
            true,
            1L);

    assertThatThrownBy(() -> service().upsertBudget(userId, request))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verify(budgetRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void upsertBudget_updatePath_currencyChange_throws422Equivalent() {
    UUID userId = UUID.randomUUID();
    Budget existing = ProvisionsTestData.budget(userId).currency("GBP").version(0L).build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    UpdateBudgetRequest request =
        ProvisionsTestData.updateBudgetRequest(
            new BigDecimal("75.00"),
            "EUR",
            new BigDecimal("5.00"),
            PriceSensitivity.moderate,
            true,
            0L);

    assertThatThrownBy(() -> service().upsertBudget(userId, request))
        .isInstanceOf(BudgetCurrencyChangeException.class);

    verify(budgetRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void upsertBudget_updatePath_noChange_doesNotBumpVersionOrPublishEvent() {
    UUID userId = UUID.randomUUID();
    Budget existing =
        ProvisionsTestData.budget(userId)
            .weeklyTarget(new BigDecimal("75.00"))
            .currency("GBP")
            .toleranceOver(new BigDecimal("5.00"))
            .priceSensitivity(PriceSensitivity.moderate)
            .enabled(true)
            .version(2L)
            .build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    UpdateBudgetRequest request =
        ProvisionsTestData.updateBudgetRequest(
            new BigDecimal("75.00"),
            "GBP",
            new BigDecimal("5.00"),
            PriceSensitivity.moderate,
            true,
            2L);

    BudgetDto result = service().upsertBudget(userId, request);

    assertThat(result.version()).isEqualTo(2L);
    verify(budgetRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void upsertBudget_updatePath_enabledFlagToggled_publishesEvent() {
    UUID userId = UUID.randomUUID();
    Budget existing = ProvisionsTestData.budget(userId).enabled(true).version(0L).build();
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
    when(budgetRepository.saveAndFlush(any(Budget.class)))
        .thenAnswer(inv -> inv.getArgument(0, Budget.class));

    UpdateBudgetRequest request =
        ProvisionsTestData.updateBudgetRequest(
            new BigDecimal("75.00"),
            "GBP",
            new BigDecimal("5.00"),
            PriceSensitivity.moderate,
            false,
            0L);

    BudgetDto result = service().upsertBudget(userId, request);

    assertThat(result.enabled()).isFalse();
    verify(eventPublisher).publishEvent(any(BudgetChangedEvent.class));
  }
}
