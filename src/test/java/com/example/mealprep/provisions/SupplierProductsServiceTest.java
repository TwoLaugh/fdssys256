package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.domain.entity.SubstitutionRecord;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.repository.BudgetRepository;
import com.example.mealprep.provisions.domain.repository.EquipmentRepository;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.domain.service.internal.ProvisionServiceImpl;
import com.example.mealprep.provisions.event.SubstitutionAcceptedEvent;
import com.example.mealprep.provisions.exception.SupplierProductNotFoundException;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit-level coverage of the supplier-product upsert, search, batch-lookup, and substitution flows
 * in {@link ProvisionServiceImpl}. Repository + event publisher are mocked; the real mappers are
 * used.
 */
@ExtendWith(MockitoExtension.class)
class SupplierProductsServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private EquipmentRepository equipmentRepository;
  @Mock private BudgetRepository budgetRepository;
  @Mock private SupplierProductRepository supplierProductRepository;

  @Mock
  private com.example.mealprep.provisions.domain.repository.WasteEntryRepository
      wasteEntryRepository;

  @Mock private ApplicationEventPublisher eventPublisher;

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
        fixedClock);
  }

  // ---------------- upsertSupplierProduct ----------------

  @Test
  void upsert_insertPath_returnsCreatedTrue_andSaves() {
    when(supplierProductRepository.findBySupplierAndProductId("tesco", "tesco:1"))
        .thenReturn(Optional.empty());
    when(supplierProductRepository.saveAndFlush(any(SupplierProduct.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ProvisionUpdateService.UpsertResult<SupplierProductDto> result =
        service()
            .upsertSupplierProduct(
                new com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest(
                    "tesco:1",
                    "tesco",
                    "Onion",
                    new BigDecimal("0.30"),
                    new BigDecimal("1.5000"),
                    "kg",
                    null,
                    null,
                    null,
                    null,
                    LocalDate.parse("2026-05-01"),
                    "onion"));

    assertThat(result.created()).isTrue();
    assertThat(result.value().productId()).isEqualTo("tesco:1");
    assertThat(result.value().substitutionHistory()).isEmpty();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void upsert_updatePath_returnsCreatedFalse_andPreservesHistory() {
    SupplierProduct existing =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .substitutionHistory(
                List.of(
                    new SubstitutionRecord(LocalDate.parse("2026-04-01"), "tesco:99", true, null)))
            .build();
    when(supplierProductRepository.findBySupplierAndProductId("tesco", "tesco:1"))
        .thenReturn(Optional.of(existing));
    when(supplierProductRepository.saveAndFlush(any(SupplierProduct.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ProvisionUpdateService.UpsertResult<SupplierProductDto> result =
        service()
            .upsertSupplierProduct(
                new com.example.mealprep.provisions.api.dto.UpsertSupplierProductRequest(
                    "tesco:1",
                    "tesco",
                    "Onion (updated name)",
                    new BigDecimal("0.32"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    LocalDate.parse("2026-05-09"),
                    "onion"));

    assertThat(result.created()).isFalse();
    assertThat(result.value().name()).isEqualTo("Onion (updated name)");
    assertThat(result.value().substitutionHistory()).hasSize(1);
    assertThat(result.value().substitutionHistory().get(0).substitutedWithProductId())
        .isEqualTo("tesco:99");
  }

  // ---------------- getSupplierProductByMappingKey ----------------

  @Test
  void getByMappingKey_picksCheapestByPricePerUnit_breakingTiesByFreshestLastChecked() {
    SupplierProduct expensive =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .pricePerUnit(new BigDecimal("2.0000"))
            .lastChecked(LocalDate.parse("2026-05-05"))
            .build();
    SupplierProduct cheap =
        ProvisionsTestData.supplierProduct("tesco", "tesco:2")
            .pricePerUnit(new BigDecimal("1.0000"))
            .lastChecked(LocalDate.parse("2026-05-01"))
            .build();
    SupplierProduct alsoCheapButOlder =
        ProvisionsTestData.supplierProduct("ocado", "ocado:1")
            .pricePerUnit(new BigDecimal("1.0000"))
            .lastChecked(LocalDate.parse("2026-04-01"))
            .build();
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(List.of("onion")))
        .thenReturn(List.of(expensive, cheap, alsoCheapButOlder));

    Optional<SupplierProductDto> result = service().getSupplierProductByMappingKey("onion");

    assertThat(result).isPresent();
    assertThat(result.get().productId()).isEqualTo("tesco:2");
  }

  @Test
  void getByMappingKey_emptyResult_returnsEmpty() {
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(List.of("ghost")))
        .thenReturn(List.of());
    assertThat(service().getSupplierProductByMappingKey("ghost")).isEmpty();
  }

  @Test
  void getByMappingKey_nullKey_returnsEmpty_andSkipsRepository() {
    assertThat(service().getSupplierProductByMappingKey(null)).isEmpty();
    verifyNoInteractions(supplierProductRepository);
  }

  // ---------------- getSupplierProductsByMappingKeys ----------------

  @Test
  void getByMappingKeysBatch_emptyInput_returnsEmptyMap_andSkipsRepository() {
    assertThat(service().getSupplierProductsByMappingKeys(List.of())).isEmpty();
    verifyNoInteractions(supplierProductRepository);
  }

  @Test
  void getByMappingKeysBatch_groupsByMappingKey_andReturnsCheapestPerKey() {
    SupplierProduct onionExpensive =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .ingredientMappingKey("onion")
            .pricePerUnit(new BigDecimal("2.0000"))
            .build();
    SupplierProduct onionCheap =
        ProvisionsTestData.supplierProduct("tesco", "tesco:2")
            .ingredientMappingKey("onion")
            .pricePerUnit(new BigDecimal("1.0000"))
            .build();
    SupplierProduct garlic =
        ProvisionsTestData.supplierProduct("ocado", "ocado:99")
            .ingredientMappingKey("garlic")
            .pricePerUnit(new BigDecimal("5.0000"))
            .build();
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(any()))
        .thenReturn(List.of(onionExpensive, onionCheap, garlic));

    Map<String, SupplierProductDto> result =
        service().getSupplierProductsByMappingKeys(List.of("onion", "garlic", "missing"));

    assertThat(result).hasSize(2);
    assertThat(result.get("onion").productId()).isEqualTo("tesco:2");
    assertThat(result.get("garlic").productId()).isEqualTo("ocado:99");
    assertThat(result).doesNotContainKey("missing");
  }

  // ---------------- search ----------------

  @Test
  void search_delegatesToRepository_andMapsResultPage() {
    SupplierProduct row = ProvisionsTestData.supplierProduct("tesco", "tesco:1").build();
    when(supplierProductRepository.search(eq("onion"), eq("tesco"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));

    Page<SupplierProductDto> result =
        service().searchSupplierProducts("onion", "tesco", Pageable.unpaged());

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).productId()).isEqualTo("tesco:1");
  }

  // ---------------- recordSubstitution ----------------

  @Test
  void recordSubstitution_appendsToHistory_andPublishesEvent() {
    UUID supplierProductId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    SupplierProduct existing =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .id(supplierProductId)
            .version(0L)
            .substitutionHistory(List.of())
            .build();
    when(supplierProductRepository.findById(supplierProductId)).thenReturn(Optional.of(existing));
    when(supplierProductRepository.saveAndFlush(any(SupplierProduct.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SupplierProductDto result =
        service()
            .recordSubstitution(
                supplierProductId,
                ProvisionsTestData.substitutionRecordDto("tesco:RED-onion"),
                true,
                actor,
                0L);

    assertThat(result.substitutionHistory()).hasSize(1);
    assertThat(result.substitutionHistory().get(0).substitutedWithProductId())
        .isEqualTo("tesco:RED-onion");
    assertThat(result.substitutionHistory().get(0).accepted()).isTrue();

    ArgumentCaptor<SubstitutionAcceptedEvent> eventCaptor =
        ArgumentCaptor.forClass(SubstitutionAcceptedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().userId()).isEqualTo(actor);
    assertThat(eventCaptor.getValue().supplierProductId()).isEqualTo(supplierProductId);
    assertThat(eventCaptor.getValue().substitutedProductId()).isEqualTo("tesco:RED-onion");
  }

  @Test
  void recordSubstitution_missingId_throws404() {
    UUID id = UUID.randomUUID();
    when(supplierProductRepository.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service()
                    .recordSubstitution(
                        id,
                        ProvisionsTestData.substitutionRecordDto("tesco:X"),
                        true,
                        UUID.randomUUID(),
                        0L))
        .isInstanceOf(SupplierProductNotFoundException.class);
  }

  @Test
  void recordSubstitution_staleExpectedVersion_throwsOptimisticLock() {
    UUID id = UUID.randomUUID();
    SupplierProduct existing =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1").id(id).version(3L).build();
    when(supplierProductRepository.findById(id)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .recordSubstitution(
                        id,
                        ProvisionsTestData.substitutionRecordDto("tesco:X"),
                        true,
                        UUID.randomUUID(),
                        1L))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }
}
