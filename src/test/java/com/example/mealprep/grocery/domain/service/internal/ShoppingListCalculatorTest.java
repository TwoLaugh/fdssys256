package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit test of the six-step {@link ShoppingListCalculator} (grocery-01b) with mocked peer query
 * services. Covers: normalised-key aggregation, the {@code pantryTrackingEnabled} gate, inventory
 * subtraction with underflow-clamp, pack-size key-match vs category fallback, staple lines, quality
 * notes, and the full / partial / none cost-projection cases. Pure mock test — no DB.
 */
@ExtendWith(MockitoExtension.class)
class ShoppingListCalculatorTest {

  private static final Instant NOW = Instant.parse("2026-05-27T12:00:00Z");
  private static final UUID USER = UUID.randomUUID();
  private static final UUID HOUSEHOLD = UUID.randomUUID();
  private static final UUID PLAN = UUID.randomUUID();

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private RecipeQueryService recipeQueryService;
  @Mock private ProvisionQueryService provisionQueryService;
  @Mock private LifestyleConfigQueryService lifestyleConfigQueryService;
  @Mock private PriceHistoryService priceHistoryService;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private ShoppingListDataGateway shoppingListDataGateway;

  private ShoppingListCalculator calculator;

  @BeforeEach
  void setUp() {
    PackSizeHeuristicLookup lookup = new PackSizeHeuristicLookup(shoppingListDataGateway);
    calculator =
        new ShoppingListCalculator(
            recipeQueryService,
            provisionQueryService,
            lifestyleConfigQueryService,
            priceHistoryService,
            householdQueryService,
            lookup,
            new PackSizeOptimiser(),
            clock);
    // Defaults: no inventory, no staples, no lifestyle config, no price aggregates, no packs.
    lenient()
        .when(provisionQueryService.listActiveInventory(any(), any(), any()))
        .thenReturn(emptyPage());
    lenient()
        .when(provisionQueryService.getStaplesNeedingReplenishment(any()))
        .thenReturn(List.of());
    lenient()
        .when(lifestyleConfigQueryService.getLifestyleConfig(any()))
        .thenReturn(Optional.empty());
    lenient()
        .when(priceHistoryService.getAggregatesByKeys(any(), anyCollection()))
        .thenReturn(Map.of());
    lenient().when(shoppingListDataGateway.findPacksByKey(any())).thenReturn(List.of());
    lenient().when(shoppingListDataGateway.findPacksByCategory(any())).thenReturn(List.of());
  }

  // ---- Step 1: aggregation by normalised key --------------------------------------------------

  @Test
  void aggregatesDemandByNormalisedKey_chickenBreastCaseFolds() {
    // Two recipes both demanding chicken breast under different casing → one bucketed line.
    RecipeDto r1 = recipe("Chicken Breast", "200", "g", 2);
    RecipeDto r2 = recipe("chicken breast", "100", "g", 2);
    when(recipeQueryService.getById(r1.id())).thenReturn(Optional.of(r1));
    when(recipeQueryService.getById(r2.id())).thenReturn(Optional.of(r2));
    PlanDto plan = planWith(slot(r1), slot(r2));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    List<ShoppingListLine> chicken =
        list.getLines().stream()
            .filter(l -> l.getIngredientMappingKey().equals("chicken breast"))
            .toList();
    assertThat(chicken).hasSize(1);
    assertThat(chicken.get(0).getRequestedQuantity()).isEqualByComparingTo("300.000");
    assertThat(chicken.get(0).getLineType()).isEqualTo(ShoppingListLineType.PLANNED_DEMAND);
  }

  @Test
  void scalesQuantityByCookedServingsPerSlot() {
    // Recipe base servings = 2, demands 200 g; slot cooked-servings = 4 → 400 g.
    RecipeDto recipe = recipe("flour", "200", "g", 2);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slotServings(recipe, 4));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(line(list, "flour").getRequestedQuantity()).isEqualByComparingTo("400.000");
  }

  // ---- Step 2: inventory gate + subtraction ---------------------------------------------------

  @Test
  void pantryTrackingDisabled_inventoryIgnored_fullerList() {
    RecipeDto recipe = recipe("flour", "1000", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    // pantry tracking absent → treated as disabled; inventory must NOT be read.

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(list.isPantryTrackingEnabled()).isFalse();
    assertThat(line(list, "flour").getRequestedQuantity()).isEqualByComparingTo("1000.000");
    verify(provisionQueryService, never()).listActiveInventory(any(), any(), any());
  }

  @Test
  void pantryTrackingEnabled_subtractsInventory_clampsUnderflowToZero() {
    RecipeDto recipe = recipe("flour", "1000", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    enablePantryTracking();
    // 1200 g on hand vs 1000 g demand → remaining clamps to 0 → no flour line.
    when(provisionQueryService.listActiveInventory(eq(USER), any(), any()))
        .thenReturn(page(inventory("flour", "1200", "g", false)));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(list.isPantryTrackingEnabled()).isTrue();
    assertThat(list.getLines().stream().anyMatch(l -> l.getIngredientMappingKey().equals("flour")))
        .isFalse();
  }

  @Test
  void pantryTrackingEnabled_partialInventory_reducesDemand() {
    RecipeDto recipe = recipe("flour", "1000", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    enablePantryTracking();
    when(provisionQueryService.listActiveInventory(eq(USER), any(), any()))
        .thenReturn(page(inventory("flour", "300", "g", false)));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(line(list, "flour").getRequestedQuantity()).isEqualByComparingTo("700.000");
  }

  // ---- Step 3: pack-size key match vs category fallback ---------------------------------------

  @Test
  void packSize_keyMatchUsed_whenKeyHasPacks() {
    RecipeDto recipe = recipe("flour", "750", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    when(shoppingListDataGateway.findPacksByKey("flour"))
        .thenReturn(List.of(packKey("flour", 500, 1), packKey("flour", 1000, 2)));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    ShoppingListLine flour = line(list, "flour");
    assertThat(flour.getSuggestedPackSizeG()).isEqualTo(1000); // 1×1kg
    assertThat(flour.getSuggestedPackCount()).isEqualTo(1);
    // Category fallback must NOT be consulted when the key matched.
    verify(shoppingListDataGateway, never()).findPacksByCategory(any());
  }

  // ---- Step 4: staples ------------------------------------------------------------------------

  @Test
  void appendsStapleReplenishmentLines() {
    PlanDto plan = planWith(); // no scheduled recipes
    when(provisionQueryService.getStaplesNeedingReplenishment(USER))
        .thenReturn(List.of(staple("salt", "Salt", "1", "items")));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    List<ShoppingListLine> staples =
        list.getLines().stream()
            .filter(l -> l.getLineType() == ShoppingListLineType.STAPLE_REPLENISHMENT)
            .toList();
    assertThat(staples).hasSize(1);
    assertThat(staples.get(0).getIngredientMappingKey()).isEqualTo("salt");
  }

  // ---- Step 5: quality notes ------------------------------------------------------------------

  @Test
  void populatesQualityNotesFromLifestyleConfig() {
    RecipeDto recipe = recipe("eggs", "6", "items", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    LifestyleConfigDocument doc =
        baseLifestyle()
            .toBuilderWith(
                new LifestyleConfigDocument.GroceryQualityPreferences(
                    "where available", "always", null, null, null));
    when(lifestyleConfigQueryService.getLifestyleConfig(USER))
        .thenReturn(Optional.of(lifestyleDto(doc)));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(line(list, "eggs").getQualityNotes()).contains("free-range eggs");
  }

  // ---- Step 6: cost projection ----------------------------------------------------------------

  @Test
  void costProjection_allAggregatesPresent_setsTotalsAndConfidence() {
    RecipeDto recipe = recipe("flour", "750", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    when(shoppingListDataGateway.findPacksByKey("flour"))
        .thenReturn(List.of(packKey("flour", 1000, 1)));
    when(priceHistoryService.getAggregatesByKeys(any(), anyCollection()))
        .thenReturn(Map.of("flour", aggregate("flour", 80, "0.900", false)));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    ShoppingListLine flour = line(list, "flour");
    assertThat(flour.getEstimatedUnitPence()).isEqualTo(80);
    assertThat(flour.getEstimatedLinePence()).isEqualTo(80); // 1 pack × 80
    assertThat(list.getEstimatedTotalPence()).isEqualTo(80);
    assertThat(list.getCostConfidence()).isEqualByComparingTo("0.900");
    assertThat(list.getStaleIngredientCount()).isZero();
  }

  @Test
  void costProjection_missingAggregate_incrementsStaleCountAndNullsLineEstimate() {
    RecipeDto recipe = recipe("flour", "750", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));
    // No aggregate for flour at all.
    when(priceHistoryService.getAggregatesByKeys(any(), anyCollection())).thenReturn(Map.of());

    ShoppingList list = calculator.calculate(USER, plan, 1);

    ShoppingListLine flour = line(list, "flour");
    assertThat(flour.getEstimatedLinePence()).isNull();
    assertThat(flour.isStaleEstimate()).isTrue();
    assertThat(list.getStaleIngredientCount()).isEqualTo(1);
    assertThat(list.getEstimatedTotalPence()).isNull(); // none → null totals, list still renders
    assertThat(list.getLines()).isNotEmpty();
  }

  @Test
  void costProjection_noAggregatesAtAll_listStillRendersWithNullTotals() {
    RecipeDto recipe = recipe("flour", "750", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(list.getEstimatedTotalPence()).isNull();
    assertThat(list.getCostConfidence()).isNull();
    assertThat(line(list, "flour")).isNotNull();
  }

  // ---- recipe read invocation count (chattiness probe) ----------------------------------------

  @Test
  void readsEachDistinctRecipeAtMostOnce() {
    RecipeDto recipe = recipe("flour", "100", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    // Same recipe scheduled in three slots.
    PlanDto plan = planWith(slot(recipe), slot(recipe), slot(recipe));

    calculator.calculate(USER, plan, 1);

    verify(recipeQueryService, times(1)).getById(recipe.id());
  }

  // ---- household resolution -------------------------------------------------------------------

  @Test
  void usesPlanHouseholdId_forCostScope() {
    RecipeDto recipe = recipe("flour", "100", "g", 1);
    when(recipeQueryService.getById(recipe.id())).thenReturn(Optional.of(recipe));
    PlanDto plan = planWith(slot(recipe));

    ShoppingList list = calculator.calculate(USER, plan, 1);

    assertThat(list.getHouseholdId()).isEqualTo(HOUSEHOLD);
    // Plan carries a household → the user-household lookup is not needed.
    verify(householdQueryService, never()).getByUserId(any());
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private void enablePantryTracking() {
    LifestyleConfigDocument doc =
        baseLifestyle().withPantry(new LifestyleConfigDocument.PantryTracking(true));
    when(lifestyleConfigQueryService.getLifestyleConfig(USER))
        .thenReturn(Optional.of(lifestyleDto(doc)));
  }

  private static LifestyleBuilder baseLifestyle() {
    return new LifestyleBuilder();
  }

  /** Tiny builder over the preference LifestyleConfigDocument's 12-arg constructor. */
  private static final class LifestyleBuilder {
    private LifestyleConfigDocument.GroceryQualityPreferences quality;
    private LifestyleConfigDocument.PantryTracking pantry;

    LifestyleConfigDocument withPantry(LifestyleConfigDocument.PantryTracking p) {
      this.pantry = p;
      return build();
    }

    LifestyleConfigDocument toBuilderWith(LifestyleConfigDocument.GroceryQualityPreferences q) {
      this.quality = q;
      return build();
    }

    LifestyleConfigDocument build() {
      return new LifestyleConfigDocument(
          null, null, null, null, null, null, null, null, null, null, quality, pantry);
    }
  }

  private static LifestyleConfigDto lifestyleDto(LifestyleConfigDocument doc) {
    return new LifestyleConfigDto(UUID.randomUUID(), USER, doc, null, 0L, NOW, NOW);
  }

  private static RecipeDto recipe(String key, String qty, String unit, int baseServings) {
    UUID recipeId = UUID.randomUUID();
    IngredientDto ing =
        new IngredientDto(
            UUID.randomUUID(),
            0,
            key,
            capitalise(key),
            new BigDecimal(qty),
            unit,
            null,
            false,
            false,
            null);
    RecipeMetadataDto meta =
        new RecipeMetadataDto(
            baseServings, 10, 20, 30, List.of(), null, null, false, "british", List.of("dinner"));
    RecipeVersionDto body =
        new RecipeVersionDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            null,
            null,
            null,
            "ready",
            NOW,
            "system",
            null,
            List.of(ing),
            List.of(),
            meta,
            null,
            null);
    return new RecipeDto(
        recipeId,
        USER,
        null,
        capitalise(key),
        null,
        1,
        body.branchId(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L,
        NOW,
        NOW,
        body,
        List.of());
  }

  private static MealSlotDto slot(RecipeDto recipe) {
    return slotServings(recipe, recipe.currentVersionBody().metadata().servings());
  }

  private static MealSlotDto slotServings(RecipeDto recipe, int servings) {
    ScheduledRecipeDto scheduled =
        new ScheduledRecipeDto(
            UUID.randomUUID(),
            recipe.id(),
            recipe.currentVersionBody().id(),
            recipe.currentBranchId(),
            servings,
            null,
            null,
            null,
            false);
    return new MealSlotDto(
        UUID.randomUUID(),
        0,
        SlotKind.DINNER,
        "Dinner",
        600,
        true,
        List.of(),
        null,
        null,
        null,
        null,
        scheduled);
  }

  private static PlanDto planWith(MealSlotDto... slots) {
    DayDto day = new DayDto(UUID.randomUUID(), LocalDate.of(2026, 6, 1), null, List.of(slots));
    return new PlanDto(
        PLAN,
        HOUSEHOLD,
        LocalDate.of(2026, 6, 1),
        1,
        null,
        PlanStatus.GENERATED,
        TriggerKind.USER_INITIATED,
        null,
        false,
        false,
        false,
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
        List.of(day),
        0L,
        NOW,
        NOW);
  }

  private static InventoryItemDto inventory(String key, String qty, String unit, boolean staple) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        USER,
        capitalise(key),
        "pantry",
        StorageLocation.CUPBOARD,
        TrackingMode.QUANTITY,
        new BigDecimal(qty),
        unit,
        null,
        StapleStatus.STOCKED,
        staple,
        null,
        key,
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        NOW,
        NOW,
        0L);
  }

  private static InventoryItemDto staple(String key, String name, String qty, String unit) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        USER,
        name,
        "pantry",
        StorageLocation.CUPBOARD,
        TrackingMode.STATUS,
        new BigDecimal(qty),
        unit,
        null,
        StapleStatus.LOW,
        true,
        null,
        key,
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        NOW,
        NOW,
        0L);
  }

  private static PackSizeHeuristic packKey(String key, int sizeG, int rank) {
    return PackSizeHeuristic.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .packSizeG(sizeG)
        .packUnit("g")
        .rank(rank)
        .build();
  }

  private static PriceAggregateDto aggregate(
      String key, int unitPence, String conf, boolean stale) {
    return new PriceAggregateDto(
        key, null, unitPence, new BigDecimal(conf), unitPence, unitPence, NOW, NOW, NOW, 3, stale);
  }

  private static ShoppingListLine line(ShoppingList list, String key) {
    return list.getLines().stream()
        .filter(l -> l.getIngredientMappingKey().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line for key " + key));
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static Page<InventoryItemDto> emptyPage() {
    return new PageImpl<>(List.of(), Pageable.unpaged(), 0);
  }

  private static Page<InventoryItemDto> page(InventoryItemDto... items) {
    return new PageImpl<>(List.of(items));
  }
}
