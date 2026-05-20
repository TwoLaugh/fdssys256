package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.api.dto.PriceSensitivity;
import com.example.mealprep.provisions.api.dto.SubstitutionRecordDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.api.dto.WasteReason;
import com.example.mealprep.provisions.api.mapper.BudgetMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapper;
import com.example.mealprep.provisions.api.mapper.EquipmentMapperImpl;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapper;
import com.example.mealprep.provisions.api.mapper.InventoryAuditMapperImpl;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.api.mapper.WasteEntryMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.Budget;
import com.example.mealprep.provisions.domain.entity.Equipment;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.SubstitutionRecord;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for the hand-written interface-default mappers and the Lombok-generated entity
 * getters in the provisions module. None of these need a Spring context or DB — they're straight
 * in-memory invocations.
 *
 * <p>The {@code @Mapper(componentModel = "spring")} interfaces in this module use {@code default}
 * methods (no MapStruct-generated impl beyond a trivial pass-through); instantiating via {@code new
 * X() {}} or {@code new XMapperImpl()} both work. We use whichever the existing tests already wire
 * up so the unit shape stays consistent.
 *
 * <p>Each entity round-trip test asserts every getter against the value the builder set. This kills
 * the "replace return with null/empty/0" mutants Pitest emits for Lombok-generated getters.
 * MapStruct-generated {@code *MapperImpl} classes are excluded by the Pitest config so we do not
 * test them.
 */
class ProvisionsMapperAndEntityTest {

  // ---------------- BudgetMapper ----------------

  private final BudgetMapper budgetMapper = new BudgetMapper() {};

  @Test
  void budgetMapper_nullEntity_returnsNull() {
    assertThat(budgetMapper.toDto(null)).isNull();
  }

  @Test
  void budgetMapper_populatedEntity_mapsEveryField_andSpendTrackingIsNull() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Budget entity =
        Budget.builder()
            .id(id)
            .userId(userId)
            .weeklyTarget(new BigDecimal("75.50"))
            .currency("GBP")
            .toleranceOver(new BigDecimal("5.00"))
            .priceSensitivity(PriceSensitivity.high)
            .enabled(true)
            .version(3L)
            .build();

    BudgetDto dto = budgetMapper.toDto(entity);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.weeklyTarget()).isEqualByComparingTo("75.50");
    assertThat(dto.currency()).isEqualTo("GBP");
    assertThat(dto.toleranceOver()).isEqualByComparingTo("5.00");
    assertThat(dto.priceSensitivity()).isEqualTo(PriceSensitivity.high);
    assertThat(dto.enabled()).isTrue();
    assertThat(dto.spendTracking()).isNull();
    assertThat(dto.version()).isEqualTo(3L);
  }

  @Test
  void budgetMapper_toDtos_nullList_returnsEmptyList() {
    assertThat(budgetMapper.toDtos(null)).isEmpty();
  }

  @Test
  void budgetMapper_toDtos_multipleEntities_mapsEach() {
    Budget a =
        Budget.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .weeklyTarget(new BigDecimal("60.00"))
            .currency("GBP")
            .toleranceOver(BigDecimal.ZERO)
            .priceSensitivity(PriceSensitivity.low)
            .enabled(false)
            .build();
    Budget b =
        Budget.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .weeklyTarget(new BigDecimal("90.00"))
            .currency("EUR")
            .toleranceOver(new BigDecimal("3.00"))
            .priceSensitivity(PriceSensitivity.high)
            .enabled(true)
            .build();

    List<BudgetDto> dtos = budgetMapper.toDtos(List.of(a, b));

    assertThat(dtos).hasSize(2);
    assertThat(dtos.get(0).currency()).isEqualTo("GBP");
    assertThat(dtos.get(1).currency()).isEqualTo("EUR");
  }

  // ---------------- EquipmentMapper ----------------

  private final EquipmentMapper equipmentMapper = new EquipmentMapperImpl();

  @Test
  void equipmentMapper_nullEntity_returnsNull() {
    assertThat(equipmentMapper.toDto(null)).isNull();
  }

  @Test
  void equipmentMapper_populatedEntity_mapsEveryField() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Equipment entity =
        Equipment.builder()
            .id(id)
            .userId(userId)
            .name("oven")
            .available(true)
            .details("Stainless steel, 4-burner")
            .version(2L)
            .build();

    EquipmentDto dto = equipmentMapper.toDto(entity);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.name()).isEqualTo("oven");
    assertThat(dto.available()).isTrue();
    assertThat(dto.details()).isEqualTo("Stainless steel, 4-burner");
    assertThat(dto.version()).isEqualTo(2L);
  }

  // ---------------- SupplierProductMapper ----------------

  private final SupplierProductMapper supplierProductMapper = new SupplierProductMapper() {};

  @Test
  void supplierProductMapper_nullEntity_returnsNull() {
    assertThat(supplierProductMapper.toDto(null)).isNull();
  }

  @Test
  void supplierProductMapper_populatedEntity_mapsEveryField_andHistoryElements() {
    UUID id = UUID.randomUUID();
    SubstitutionRecord rec =
        new SubstitutionRecord(LocalDate.parse("2026-05-09"), "tesco:99", true, "no red onion");
    SupplierProduct entity =
        SupplierProduct.builder()
            .id(id)
            .productId("tesco:1")
            .supplier("tesco")
            .name("Onion")
            .price(new BigDecimal("0.30"))
            .pricePerUnit(new BigDecimal("1.5000"))
            .unit("kg")
            .packSizeG(200)
            .packSizeUnit("pcs")
            .category("vegetables")
            .clubcardPrice(new BigDecimal("0.25"))
            .lastChecked(LocalDate.parse("2026-05-01"))
            .substitutionHistory(List.of(rec))
            .ingredientMappingKey("onion")
            .version(4L)
            .build();

    SupplierProductDto dto = supplierProductMapper.toDto(entity);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.productId()).isEqualTo("tesco:1");
    assertThat(dto.supplier()).isEqualTo("tesco");
    assertThat(dto.name()).isEqualTo("Onion");
    assertThat(dto.price()).isEqualByComparingTo("0.30");
    assertThat(dto.pricePerUnit()).isEqualByComparingTo("1.5000");
    assertThat(dto.unit()).isEqualTo("kg");
    assertThat(dto.packSizeG()).isEqualTo(200);
    assertThat(dto.packSizeUnit()).isEqualTo("pcs");
    assertThat(dto.category()).isEqualTo("vegetables");
    assertThat(dto.clubcardPrice()).isEqualByComparingTo("0.25");
    assertThat(dto.lastChecked()).isEqualTo(LocalDate.parse("2026-05-01"));
    assertThat(dto.ingredientMappingKey()).isEqualTo("onion");
    assertThat(dto.version()).isEqualTo(4L);
    assertThat(dto.substitutionHistory()).hasSize(1);
    SubstitutionRecordDto historyDto = dto.substitutionHistory().get(0);
    assertThat(historyDto.date()).isEqualTo(LocalDate.parse("2026-05-09"));
    assertThat(historyDto.substitutedWithProductId()).isEqualTo("tesco:99");
    assertThat(historyDto.accepted()).isTrue();
    assertThat(historyDto.notes()).isEqualTo("no red onion");
  }

  @Test
  void supplierProductMapper_nullHistory_mapsToEmptyList() {
    SupplierProduct entity =
        SupplierProduct.builder()
            .id(UUID.randomUUID())
            .productId("tesco:2")
            .supplier("tesco")
            .substitutionHistory(null)
            .build();

    SupplierProductDto dto = supplierProductMapper.toDto(entity);

    assertThat(dto.substitutionHistory()).isEmpty();
  }

  @Test
  void supplierProductMapper_toRecordDto_nullRecord_returnsNull() {
    assertThat(SupplierProductMapper.toRecordDto(null)).isNull();
  }

  // ---------------- InventoryAuditMapper ----------------

  private final InventoryAuditMapper inventoryAuditMapper = new InventoryAuditMapperImpl();

  @Test
  void inventoryAuditMapper_nullEntity_returnsNull() {
    assertThat(inventoryAuditMapper.toDto(null)).isNull();
  }

  @Test
  void inventoryAuditMapper_populatedEntity_mapsEveryField() {
    UUID id = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    ObjectNode prev = JsonNodeFactory.instance.objectNode().put("quantity", "100");
    ObjectNode next = JsonNodeFactory.instance.objectNode().put("quantity", "50");
    Instant when = Instant.parse("2026-05-09T10:00:00Z");
    InventoryAuditLog entity =
        new InventoryAuditLog(
            id, itemId, actorUserId, AuditActor.USER, actorUserId, "quantity", prev, next, when);

    InventoryAuditEntryDto dto = inventoryAuditMapper.toDto(entity);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.inventoryItemId()).isEqualTo(itemId);
    assertThat(dto.actor()).isEqualTo(AuditActor.USER);
    assertThat(dto.actorUserId()).isEqualTo(actorUserId);
    assertThat(dto.fieldChanged()).isEqualTo("quantity");
    assertThat(dto.previousValue()).isEqualTo(prev);
    assertThat(dto.newValue()).isEqualTo(next);
    assertThat(dto.occurredAt()).isEqualTo(when);
  }

  // ---------------- WasteEntryMapper ----------------

  private final WasteEntryMapper wasteEntryMapper = new WasteEntryMapper() {};

  @Test
  void wasteEntryMapper_nullEntity_returnsNull() {
    assertThat(wasteEntryMapper.toDto(null)).isNull();
  }

  @Test
  void wasteEntryMapper_populatedEntity_mapsEveryField() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-05-09T11:00:00Z");
    WasteEntry entity =
        WasteEntry.builder()
            .id(id)
            .userId(userId)
            .inventoryItemId(itemId)
            .itemName("Cheddar")
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .reason(WasteReason.EXPIRED)
            .costEstimate(new BigDecimal("2.50"))
            .occurredOn(LocalDate.parse("2026-05-08"))
            .notes("moulded")
            .createdAt(createdAt)
            .build();

    WasteEntryDto dto = wasteEntryMapper.toDto(entity);

    assertThat(dto).isNotNull();
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.inventoryItemId()).isEqualTo(itemId);
    assertThat(dto.itemName()).isEqualTo("Cheddar");
    assertThat(dto.quantity()).isEqualByComparingTo("100.000");
    assertThat(dto.unit()).isEqualTo("g");
    assertThat(dto.reason()).isEqualTo(WasteReason.EXPIRED);
    assertThat(dto.costEstimate()).isEqualByComparingTo("2.50");
    assertThat(dto.occurredOn()).isEqualTo(LocalDate.parse("2026-05-08"));
    assertThat(dto.notes()).isEqualTo("moulded");
    assertThat(dto.createdAt()).isEqualTo(createdAt);
  }

  // ---------------- Entity getters — round-trip for Lombok-generated accessors ----------------

  @Test
  void budgetEntity_getters_returnBuilderValues() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant created = Instant.parse("2026-05-09T10:00:00Z");
    Instant updated = Instant.parse("2026-05-10T10:00:00Z");
    Budget b =
        Budget.builder()
            .id(id)
            .userId(userId)
            .weeklyTarget(new BigDecimal("80.00"))
            .currency("GBP")
            .toleranceOver(new BigDecimal("4.00"))
            .priceSensitivity(PriceSensitivity.moderate)
            .enabled(true)
            .version(7L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(b.getId()).isEqualTo(id);
    assertThat(b.getUserId()).isEqualTo(userId);
    assertThat(b.getWeeklyTarget()).isEqualByComparingTo("80.00");
    assertThat(b.getCurrency()).isEqualTo("GBP");
    assertThat(b.getToleranceOver()).isEqualByComparingTo("4.00");
    assertThat(b.getPriceSensitivity()).isEqualTo(PriceSensitivity.moderate);
    assertThat(b.isEnabled()).isTrue();
    assertThat(b.getVersion()).isEqualTo(7L);
    assertThat(b.getCreatedAt()).isEqualTo(created);
    assertThat(b.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void equipmentEntity_getters_returnBuilderValues() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant created = Instant.parse("2026-05-09T10:00:00Z");
    Instant updated = Instant.parse("2026-05-10T10:00:00Z");
    Equipment e =
        Equipment.builder()
            .id(id)
            .userId(userId)
            .name("air-fryer")
            .available(false)
            .details("dual-zone, 9L")
            .version(2L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(e.getId()).isEqualTo(id);
    assertThat(e.getUserId()).isEqualTo(userId);
    assertThat(e.getName()).isEqualTo("air-fryer");
    assertThat(e.isAvailable()).isFalse();
    assertThat(e.getDetails()).isEqualTo("dual-zone, 9L");
    assertThat(e.getVersion()).isEqualTo(2L);
    assertThat(e.getCreatedAt()).isEqualTo(created);
    assertThat(e.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void equipmentEntity_availableTrue_andDetailsNonEmpty_distinguishMutants() {
    Equipment available =
        Equipment.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .name("oven")
            .available(true)
            .details("4-burner")
            .build();
    assertThat(available.isAvailable()).isTrue();
    assertThat(available.getDetails()).isEqualTo("4-burner");
  }

  @Test
  void supplierProductEntity_getters_returnBuilderValues() {
    UUID id = UUID.randomUUID();
    Instant created = Instant.parse("2026-05-09T10:00:00Z");
    Instant updated = Instant.parse("2026-05-10T10:00:00Z");
    SupplierProduct sp =
        SupplierProduct.builder()
            .id(id)
            .productId("tesco:abc")
            .supplier("tesco")
            .name("Bread")
            .price(new BigDecimal("0.80"))
            .pricePerUnit(new BigDecimal("1.6000"))
            .unit("g")
            .packSizeG(500)
            .packSizeUnit("g")
            .category("bakery")
            .clubcardPrice(new BigDecimal("0.65"))
            .lastChecked(LocalDate.parse("2026-05-01"))
            .ingredientMappingKey("bread")
            .substitutionHistory(List.of())
            .version(9L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(sp.getId()).isEqualTo(id);
    assertThat(sp.getProductId()).isEqualTo("tesco:abc");
    assertThat(sp.getSupplier()).isEqualTo("tesco");
    assertThat(sp.getName()).isEqualTo("Bread");
    assertThat(sp.getPrice()).isEqualByComparingTo("0.80");
    assertThat(sp.getPricePerUnit()).isEqualByComparingTo("1.6000");
    assertThat(sp.getUnit()).isEqualTo("g");
    assertThat(sp.getPackSizeG()).isEqualTo(500);
    assertThat(sp.getPackSizeUnit()).isEqualTo("g");
    assertThat(sp.getCategory()).isEqualTo("bakery");
    assertThat(sp.getClubcardPrice()).isEqualByComparingTo("0.65");
    assertThat(sp.getLastChecked()).isEqualTo(LocalDate.parse("2026-05-01"));
    assertThat(sp.getIngredientMappingKey()).isEqualTo("bread");
    assertThat(sp.getSubstitutionHistory()).isEmpty();
    assertThat(sp.getVersion()).isEqualTo(9L);
    assertThat(sp.getCreatedAt()).isEqualTo(created);
    assertThat(sp.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void wasteEntryEntity_getters_returnBuilderValues() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    Instant created = Instant.parse("2026-05-09T10:00:00Z");
    WasteEntry w =
        WasteEntry.builder()
            .id(id)
            .userId(userId)
            .inventoryItemId(itemId)
            .itemName("Spinach")
            .quantity(new BigDecimal("50.000"))
            .unit("g")
            .reason(WasteReason.SPOILED_EARLY)
            .costEstimate(new BigDecimal("1.20"))
            .occurredOn(LocalDate.parse("2026-05-08"))
            .notes("slimy at the back of the fridge")
            .createdAt(created)
            .build();

    assertThat(w.getId()).isEqualTo(id);
    assertThat(w.getUserId()).isEqualTo(userId);
    assertThat(w.getInventoryItemId()).isEqualTo(itemId);
    assertThat(w.getItemName()).isEqualTo("Spinach");
    assertThat(w.getQuantity()).isEqualByComparingTo("50.000");
    assertThat(w.getUnit()).isEqualTo("g");
    assertThat(w.getReason()).isEqualTo(WasteReason.SPOILED_EARLY);
    assertThat(w.getCostEstimate()).isEqualByComparingTo("1.20");
    assertThat(w.getOccurredOn()).isEqualTo(LocalDate.parse("2026-05-08"));
    assertThat(w.getNotes()).isEqualTo("slimy at the back of the fridge");
    assertThat(w.getCreatedAt()).isEqualTo(created);
  }

  @Test
  void wasteEntryEntity_builder_returnsNonNull() {
    // Kill NullReturnVals on builder() factory.
    assertThat(WasteEntry.builder()).isNotNull();
  }

  // ---------------- InventoryItem.isStaple discriminator ----------------

  @Test
  void inventoryItem_isStapleFalse_isReadBack() {
    // Lombok-generated isStaple() boolean — kill the BooleanTrueReturnVals mutant.
    com.example.mealprep.provisions.domain.entity.InventoryItem item =
        com.example.mealprep.provisions.domain.entity.InventoryItem.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .name("Cheddar")
            .category("dairy")
            .storageLocation(com.example.mealprep.provisions.domain.entity.StorageLocation.FRIDGE)
            .trackingMode(com.example.mealprep.provisions.domain.entity.TrackingMode.QUANTITY)
            .quantity(new BigDecimal("100.000"))
            .unit("g")
            .isStaple(false)
            .source(com.example.mealprep.provisions.domain.entity.ItemSource.MANUAL_ADD)
            .itemStatus(com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE)
            .build();
    assertThat(item.isStaple()).isFalse();
  }

  @Test
  void inventoryItem_isStapleTrue_isReadBack() {
    com.example.mealprep.provisions.domain.entity.InventoryItem item =
        com.example.mealprep.provisions.domain.entity.InventoryItem.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .name("Salt")
            .category("spice")
            .storageLocation(
                com.example.mealprep.provisions.domain.entity.StorageLocation.SPICE_RACK)
            .trackingMode(com.example.mealprep.provisions.domain.entity.TrackingMode.STATUS)
            .status(com.example.mealprep.provisions.domain.entity.StapleStatus.STOCKED)
            .isStaple(true)
            .source(com.example.mealprep.provisions.domain.entity.ItemSource.MANUAL_ADD)
            .itemStatus(com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE)
            .build();
    assertThat(item.isStaple()).isTrue();
  }
}
