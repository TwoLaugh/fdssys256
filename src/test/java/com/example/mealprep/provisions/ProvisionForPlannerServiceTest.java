package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.api.mapper.WasteEntryMapper;
import com.example.mealprep.provisions.domain.entity.Budget;
import com.example.mealprep.provisions.domain.entity.Equipment;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.repository.BudgetRepository;
import com.example.mealprep.provisions.domain.repository.EquipmentRepository;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.domain.repository.WasteEntryRepository;
import com.example.mealprep.provisions.domain.service.internal.ProvisionServiceImpl;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test for the planner-bundle aggregator on {@link ProvisionServiceImpl}. Repositories +
 * household query are mocked at the module boundary; real MapStruct-generated mappers run so the
 * mapping shape matches production.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionForPlannerServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private InventoryAuditLogRepository auditLogRepository;
  @Mock private EquipmentRepository equipmentRepository;
  @Mock private BudgetRepository budgetRepository;
  @Mock private SupplierProductRepository supplierProductRepository;
  @Mock private WasteEntryRepository wasteEntryRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private HouseholdQueryService householdQueryService;

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

  private final Instant now = Instant.parse("2026-05-09T10:00:00Z");
  private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

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
        null);
  }

  // ---------------- empty user ----------------

  @Test
  void emptyUser_returnsEmptyBundle_withZeroCoverageAndFalseRampUp() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    ProvisionForPlannerBundleDto bundle = service().getBundle(userId);

    assertThat(bundle.userId()).isEqualTo(userId);
    assertThat(bundle.activeInventory()).isEmpty();
    assertThat(bundle.staplesAtLowOrOut()).isEmpty();
    assertThat(bundle.equipment()).isEmpty();
    assertThat(bundle.budget()).isNull();
    assertThat(bundle.supplierPricesByMappingKey()).isEmpty();
    assertThat(bundle.staleness().supplierCacheCoverageBps()).isZero();
    assertThat(bundle.staleness().inRampUpWindow()).isFalse();
    assertThat(bundle.staleness().generatedAt()).isEqualTo(now);

    // No supplier lookup when keys empty.
    verifyNoInteractions(supplierProductRepository);
  }

  // ---------------- populated user ----------------

  @Test
  void populatedUser_returnsBundleWithAllSections_andCorrectCoverage() {
    UUID userId = UUID.randomUUID();

    InventoryItem onion =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name("Onion")
            .ingredientMappingKey("onion")
            .build();
    InventoryItem cheese =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name("Cheddar")
            .ingredientMappingKey("cheese:cheddar")
            .build();
    InventoryItem saltStaple =
        ProvisionsTestData.statusTrackedItem(userId)
            .name("Salt")
            .status(StapleStatus.LOW)
            .ingredientMappingKey("salt")
            .build();

    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of(onion, cheese));
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of(saltStaple));

    Equipment hob = ProvisionsTestData.equipment(userId, "induction_hob").build();
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of(hob));

    Budget budget = ProvisionsTestData.budget(userId).build();
    budget.setVersion(0);
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.of(budget));

    // Only onion has a supplier product → 1 of 3 requested keys covered → bps = 3333.
    SupplierProduct onionSp =
        ProvisionsTestData.supplierProduct("tesco", "tesco:onion-1")
            .ingredientMappingKey("onion")
            .build();
    onionSp.setVersion(0);
    ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(keysCaptor.capture()))
        .thenReturn(List.of(onionSp));

    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    ProvisionForPlannerBundleDto bundle = service().getBundle(userId);

    assertThat(bundle.activeInventory()).hasSize(2);
    assertThat(bundle.staplesAtLowOrOut()).hasSize(1);
    assertThat(bundle.equipment()).hasSize(1);
    assertThat(bundle.budget()).isNotNull();
    assertThat(bundle.budget().userId()).isEqualTo(userId);
    assertThat(bundle.supplierPricesByMappingKey()).hasSize(1);
    assertThat(bundle.supplierPricesByMappingKey()).containsKey("onion");
    assertThat(bundle.staleness().supplierCacheCoverageBps()).isEqualTo(3333);
    assertThat(keysCaptor.getValue()).containsExactlyInAnyOrder("onion", "cheese:cheddar", "salt");
  }

  // ---------------- coverage math ----------------

  @Test
  void coverageBps_2keys1covered_is5000() {
    UUID userId = UUID.randomUUID();
    InventoryItem a =
        ProvisionsTestData.quantityTrackedItem(userId).ingredientMappingKey("alpha").build();
    InventoryItem b =
        ProvisionsTestData.quantityTrackedItem(userId).ingredientMappingKey("beta").build();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of(a, b));
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    SupplierProduct sp =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .ingredientMappingKey("alpha")
            .build();
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(any())).thenReturn(List.of(sp));

    ProvisionForPlannerBundleDto bundle = service().getBundle(userId);

    assertThat(bundle.staleness().supplierCacheCoverageBps()).isEqualTo(5000);
  }

  // ---------------- top-N cap ----------------

  @Test
  void topNCap_50keys_appliedAlphabetically() {
    UUID userId = UUID.randomUUID();
    // Build 60 items with keys "key-00".."key-59".
    List<InventoryItem> items =
        IntStream.range(0, 60)
            .mapToObj(
                i ->
                    ProvisionsTestData.quantityTrackedItem(userId)
                        .ingredientMappingKey(String.format("key-%02d", i))
                        .build())
            .toList();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(items);
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(keysCaptor.capture()))
        .thenReturn(List.of());

    service().getBundle(userId);

    Collection<String> requested = keysCaptor.getValue();
    assertThat(requested).hasSize(50);
    // Alphabetical determinism: first 50 keys are "key-00".."key-49".
    assertThat(requested).contains("key-00", "key-49");
    assertThat(requested).doesNotContain("key-50", "key-59");
  }

  // ---------------- lastChecked DESC tie-break ----------------

  @Test
  void supplierProduct_tieBreakBy_lastCheckedDescending() {
    UUID userId = UUID.randomUUID();
    InventoryItem onion =
        ProvisionsTestData.quantityTrackedItem(userId).ingredientMappingKey("onion").build();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of(onion));
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    SupplierProduct older =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .ingredientMappingKey("onion")
            .lastChecked(LocalDate.parse("2026-04-01"))
            .name("OLDER")
            .build();
    SupplierProduct newer =
        ProvisionsTestData.supplierProduct("tesco", "tesco:2")
            .ingredientMappingKey("onion")
            .lastChecked(LocalDate.parse("2026-05-01"))
            .name("NEWER")
            .build();
    // Return in arbitrary order — winner should always be the newer one.
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(any()))
        .thenReturn(List.of(older, newer));

    ProvisionForPlannerBundleDto bundle = service().getBundle(userId);

    assertThat(bundle.supplierPricesByMappingKey().get("onion").name()).isEqualTo("NEWER");
  }

  // ---------------- inRampUpWindow ----------------

  @Test
  void inRampUpWindow_true_whenMembershipYoungerThan56Days() {
    UUID userId = UUID.randomUUID();
    Instant joined = now.minusSeconds(7L * 24L * 3600L); // 7 days ago
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            userId,
            HouseholdRole.primary,
            "alice",
            0,
            joined,
            0);
    HouseholdDto household =
        new HouseholdDto(UUID.randomUUID(), "Casa", userId, List.of(member), joined, 0);
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.of(household));

    assertThat(service().getBundle(userId).staleness().inRampUpWindow()).isTrue();
  }

  @Test
  void inRampUpWindow_false_whenMembershipOlderThan56Days() {
    UUID userId = UUID.randomUUID();
    Instant joined = now.minusSeconds(100L * 24L * 3600L); // 100 days ago
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            userId,
            HouseholdRole.primary,
            "alice",
            0,
            joined,
            0);
    HouseholdDto household =
        new HouseholdDto(UUID.randomUUID(), "Casa", userId, List.of(member), joined, 0);
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.of(household));

    assertThat(service().getBundle(userId).staleness().inRampUpWindow()).isFalse();
  }

  @Test
  void inRampUpWindow_false_whenCallerHasNoHouseholdMembership() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getBundle(userId).staleness().inRampUpWindow()).isFalse();
  }

  // ---------------- determinism ----------------

  @Test
  void determinism_sameStateTwoCalls_byteIdentical_moduloGeneratedAt() {
    UUID userId = UUID.randomUUID();
    InventoryItem a =
        ProvisionsTestData.quantityTrackedItem(userId).ingredientMappingKey("onion").build();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of(a));
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    SupplierProduct sp =
        ProvisionsTestData.supplierProduct("tesco", "tesco:1")
            .ingredientMappingKey("onion")
            .build();
    when(supplierProductRepository.findAllByIngredientMappingKeyIn(any())).thenReturn(List.of(sp));

    ProvisionForPlannerBundleDto first = service().getBundle(userId);
    ProvisionForPlannerBundleDto second = service().getBundle(userId);

    assertThat(first.userId()).isEqualTo(second.userId());
    assertThat(first.activeInventory()).isEqualTo(second.activeInventory());
    assertThat(first.staplesAtLowOrOut()).isEqualTo(second.staplesAtLowOrOut());
    assertThat(first.equipment()).isEqualTo(second.equipment());
    assertThat(first.budget()).isEqualTo(second.budget());
    assertThat(first.supplierPricesByMappingKey()).isEqualTo(second.supplierPricesByMappingKey());
    assertThat(first.staleness().supplierCacheCoverageBps())
        .isEqualTo(second.staleness().supplierCacheCoverageBps());
    assertThat(first.staleness().inRampUpWindow()).isEqualTo(second.staleness().inRampUpWindow());
    // generatedAt equal under the fixed clock.
    assertThat(first.staleness().generatedAt()).isEqualTo(second.staleness().generatedAt());
  }

  // ---------------- staples filter ----------------

  @Test
  void staplesFilter_passesLowAndOutOnly_notStocked() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    ArgumentCaptor<Collection<StapleStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), statuses.capture()))
        .thenReturn(List.of());

    service().getBundle(userId);

    assertThat(statuses.getValue())
        .containsExactlyInAnyOrderElementsOf(Set.of(StapleStatus.LOW, StapleStatus.OUT));
  }

  // ---------------- repo call count ----------------

  @Test
  void repoCallCount_emptyUser_invokesFiveRepoCallsPlusOneHousehold() {
    UUID userId = UUID.randomUUID();
    when(inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE))
        .thenReturn(List.of());
    when(inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(
            eq(userId), any(Collection.class)))
        .thenReturn(List.of());
    when(equipmentRepository.findAllByUserIdAndAvailableTrue(userId)).thenReturn(List.of());
    when(budgetRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(householdQueryService.getByUserId(userId)).thenReturn(Optional.empty());

    service().getBundle(userId);

    verify(inventoryItemRepository, times(1))
        .findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE);
    verify(inventoryItemRepository, times(1))
        .findAllByUserIdAndIsStapleTrueAndStatusIn(eq(userId), any(Collection.class));
    verify(equipmentRepository, times(1)).findAllByUserIdAndAvailableTrue(userId);
    verify(budgetRepository, times(1)).findByUserId(userId);
    verify(householdQueryService, times(1)).getByUserId(userId);
    // No supplier-product fetch when no keys → 0 calls.
    verifyNoInteractions(supplierProductRepository);
  }
}
