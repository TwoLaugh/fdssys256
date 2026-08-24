package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.core.ingredient.IngredientUnitConverter;
import com.example.mealprep.grocery.api.dto.PriceAggregateDto;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.GroceryQualityPreferences;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier-1 deterministic six-step shopping-list calculator (grocery-01b). Per lld/grocery.md §Flow 1
 * lines 858-873. Builds an UNSAVED {@link ShoppingList} aggregate (parent + lines) from the plan +
 * provisions + preference + price-history reads; {@code GroceryServiceImpl.recalculate} persists
 * it.
 *
 * <p>The six steps: (1) aggregate planned demand by normalised key, (2) subtract inventory gated on
 * {@code pantryTrackingEnabled}, (3) pack-size heuristics, (4) add staples, (5) quality notes, (6)
 * cost projection (one batched {@link PriceHistoryService#getAggregatesByKeys} call).
 *
 * <p><b>Recipe ingredient read (verified shape).</b> {@link ScheduledRecipeDto} carries only {@code
 * recipeId}/{@code recipeVersionId}/{@code servings} — no inline ingredient list — so the
 * calculator reads ingredients via {@link RecipeQueryService#getById(UUID)} ({@code
 * currentVersionBody().ingredients()}), one read per DISTINCT recipe (deduplicated). There is no
 * {@code getByIds} batch on the recipe surface; if the per-recipe walk pushes a recalculate over
 * the LLD's ≤5-SQL trip-wire ({@code ShoppingListCalculatorIT} measures it), the optimisation is a
 * new planner-side {@code PlanQueryService.getPlanForGrocery} bundle (a separate planner ticket —
 * NOT written here).
 *
 * <p><b>Cooked-servings per slot</b> = {@link ScheduledRecipeDto#servings()} (the per-slot
 * headcount the planner scheduled). The recipe version's own metadata servings is the basis; v1
 * scales linearly by {@code slot.servings / recipeBaseServings}.
 *
 * <p>Package-private internal plumbing — invoked only by {@link GroceryServiceImpl}.
 */
@Component
class ShoppingListCalculator {

  private static final String DEFAULT_CURRENCY = "GBP";

  private final RecipeQueryService recipeQueryService;
  private final ProvisionQueryService provisionQueryService;
  private final LifestyleConfigQueryService lifestyleConfigQueryService;
  private final PriceHistoryService priceHistoryService;
  private final HouseholdQueryService householdQueryService;
  private final PackSizeHeuristicLookup packSizeHeuristicLookup;
  private final PackSizeOptimiser packSizeOptimiser;
  private final Clock clock;

  ShoppingListCalculator(
      RecipeQueryService recipeQueryService,
      ProvisionQueryService provisionQueryService,
      LifestyleConfigQueryService lifestyleConfigQueryService,
      // @Lazy breaks the construction cycle: PriceHistoryService is implemented by
      // GroceryServiceImpl, which itself injects this calculator (LLD Flow 1 step 6). The lazy
      // proxy
      // is resolved on first use (inside calculate), after both beans are constructed.
      @org.springframework.context.annotation.Lazy PriceHistoryService priceHistoryService,
      HouseholdQueryService householdQueryService,
      PackSizeHeuristicLookup packSizeHeuristicLookup,
      PackSizeOptimiser packSizeOptimiser,
      Clock clock) {
    this.recipeQueryService = recipeQueryService;
    this.provisionQueryService = provisionQueryService;
    this.lifestyleConfigQueryService = lifestyleConfigQueryService;
    this.priceHistoryService = priceHistoryService;
    this.householdQueryService = householdQueryService;
    this.packSizeHeuristicLookup = packSizeHeuristicLookup;
    this.packSizeOptimiser = packSizeOptimiser;
    this.clock = clock;
  }

  /**
   * Run the six-step pipeline for {@code (planId, planGeneration)} and return the unsaved {@link
   * ShoppingList}. The caller supplies the plan (already fetched for the generation check) to avoid
   * a second {@code getPlanById}.
   */
  @Transactional
  ShoppingList calculate(UUID userId, PlanDto plan, int planGeneration) {
    UUID householdId = resolveHouseholdId(userId, plan);

    // ---- Step 1: aggregate planned demand ----
    Map<String, IngredientDemand> demand = aggregatePlannedDemand(plan);

    // ---- Step 2: subtract inventory (gated on pantryTrackingEnabled) ----
    LifestyleConfigDocument lifestyle =
        lifestyleConfigQueryService
            .getLifestyleConfig(userId)
            .map(LifestyleConfigDto::document)
            .orElse(null);
    boolean pantryTrackingEnabled = pantryTrackingEnabled(lifestyle);
    if (pantryTrackingEnabled) {
      subtractInventory(demand, activeInventory(userId));
    }

    // ---- Step 3 + 5: pack-size heuristics + quality notes on the planned lines ----
    GroceryQualityPreferences quality =
        lifestyle == null ? null : lifestyle.groceryQualityPreferences();
    Map<String, List<PackSizeHeuristic>> packsByKey =
        packSizeHeuristicLookup.preload(demand.keySet());

    List<ShoppingListLine> lines = new ArrayList<>();
    for (IngredientDemand d : demand.values()) {
      if (!d.isPositive()) {
        continue; // fully met by inventory — no line
      }
      lines.add(plannedLine(d, packsByKey, quality));
    }

    // ---- Step 4: add staples needing replenishment (LOW/OUT) ----
    for (InventoryItemDto staple : provisionQueryService.getStaplesNeedingReplenishment(userId)) {
      lines.add(stapleLine(staple, quality));
    }

    // ---- Step 6: cost projection (one batched aggregate query) ----
    CostProjection cost = projectCost(householdId, lines);

    ShoppingList list =
        ShoppingList.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .householdId(householdId)
            .planId(plan.id())
            .planGeneration(planGeneration)
            .generatedAt(clock.instant())
            .estimatedTotalPence(cost.totalPence())
            .estimatedTotalMinPence(cost.minPence())
            .estimatedTotalMaxPence(cost.maxPence())
            .estimatedTotalCurrency(DEFAULT_CURRENCY)
            .costConfidence(cost.confidence())
            .staleIngredientCount(cost.staleCount())
            .pantryTrackingEnabled(pantryTrackingEnabled)
            .version(0L)
            .lines(new ArrayList<>())
            .build();
    for (ShoppingListLine line : lines) {
      line.setShoppingList(list);
      list.getLines().add(line);
    }
    return list;
  }

  // ---- Step 1 ---------------------------------------------------------------------------------

  private Map<String, IngredientDemand> aggregatePlannedDemand(PlanDto plan) {
    Map<String, IngredientDemand> demand = new LinkedHashMap<>();
    if (plan.days() == null) {
      return demand;
    }
    // Collect distinct recipe ids first so each recipe is read at most once.
    Map<UUID, RecipeDto> recipeCache = new LinkedHashMap<>();
    for (DayDto day : plan.days()) {
      if (day.slots() == null) {
        continue;
      }
      for (MealSlotDto slot : day.slots()) {
        ScheduledRecipeDto scheduled = slot.scheduledRecipe();
        if (scheduled == null || scheduled.recipeId() == null) {
          continue; // empty slot (eating out / fasting)
        }
        // In-meal ingredient additions are extra shopping lines (their own USDA-resolved grams),
        // independent of whether the main recipe resolves below.
        accumulateAdditions(demand, scheduled.additions());
        // SIDE_RECIPE additions: buy the side dish's own ingredients (one serving each).
        accumulateSideRecipeAdditions(demand, scheduled.additions(), recipeCache);
        RecipeDto recipe =
            recipeCache.computeIfAbsent(
                scheduled.recipeId(), id -> recipeQueryService.getById(id).orElse(null));
        if (recipe == null || recipe.currentVersionBody() == null) {
          continue;
        }
        List<IngredientDto> ingredients = recipe.currentVersionBody().ingredients();
        if (ingredients == null) {
          continue;
        }
        BigDecimal scale = servingsScale(recipe, scheduled);
        for (IngredientDto ing : ingredients) {
          if (ing.optional()) {
            continue; // optional ingredients are not auto-added to the shopping list
          }
          accumulate(demand, ing, scale);
        }
      }
    }
    return demand;
  }

  /**
   * Linear scale = cooked-servings-per-slot / recipe-base-servings (clamped ≥ 1 in denominator).
   */
  private static BigDecimal servingsScale(RecipeDto recipe, ScheduledRecipeDto scheduled) {
    int base =
        recipe.currentVersionBody().metadata() != null
            ? recipe.currentVersionBody().metadata().servings()
            : 0;
    if (base <= 0) {
      base = 1;
    }
    int slotServings = scheduled.servings() > 0 ? scheduled.servings() : base;
    // Phase 1b: also scale by the per-person portion factor (the main is eaten at N servings/person
    // to hit the calorie target), so we buy enough food for the scaled plate.
    BigDecimal portion =
        scheduled.portionFactor() == null ? BigDecimal.ONE : scheduled.portionFactor();
    return BigDecimal.valueOf(slotServings)
        .divide(BigDecimal.valueOf(base), 6, RoundingMode.HALF_UP)
        .multiply(portion);
  }

  private static void accumulate(
      Map<String, IngredientDemand> demand, IngredientDto ing, BigDecimal scale) {
    String key = IngredientMappingKeys.normalise(ing.ingredientMappingKey());
    if (key == null || key.isEmpty() || ing.quantity() == null) {
      return;
    }
    BigDecimal scaled = ing.quantity().multiply(scale);
    // Convert to grams so heterogeneous recipe units (2 tbsp + 1 cup + 1 clove) aggregate
    // coherently per canonical ingredient and the pack optimiser can compute a real purchase +
    // leftover. Falls back to the raw unit when the amount cannot be confidently converted
    // (unknown count-ingredient / unrecognised unit) — those keys just won't pack-match.
    Optional<BigDecimal> grams = IngredientUnitConverter.toGrams(scaled, ing.unit(), key);
    BigDecimal qty = grams.orElse(scaled);
    String unit = grams.isPresent() ? "g" : ing.unit();
    IngredientDemand existing = demand.get(key);
    if (existing == null) {
      demand.put(key, new IngredientDemand(key, ing.displayName(), qty, unit, null, null));
    } else {
      demand.put(key, existing.add(qty));
    }
  }

  /**
   * Accumulate in-meal INGREDIENT additions as shopping demand by their USDA-resolved grams (one
   * portion per attached slot — so a week's worth sums across the seven carrier slots). SIDE_RECIPE
   * additions are recipe-backed — see {@link #accumulateSideRecipeAdditions}.
   */
  private static void accumulateAdditions(
      Map<String, IngredientDemand> demand, List<Addition> additions) {
    if (additions == null) {
      return;
    }
    for (Addition a : additions) {
      if (a == null || a.kind() != AdditionKind.INGREDIENT) {
        continue;
      }
      String key = IngredientMappingKeys.normalise(a.ingredientMappingKey());
      if (key == null || key.isEmpty() || a.grams() == null) {
        continue;
      }
      IngredientDemand existing = demand.get(key);
      if (existing == null) {
        demand.put(key, new IngredientDemand(key, a.name(), a.grams(), "g", null, null));
      } else {
        demand.put(key, existing.add(a.grams()));
      }
    }
  }

  /**
   * Accumulate SIDE_RECIPE additions by buying their underlying recipe's ingredients (one serving
   * each), reusing the same recipe cache as the main walk so each recipe is read at most once.
   */
  private void accumulateSideRecipeAdditions(
      Map<String, IngredientDemand> demand,
      List<Addition> additions,
      Map<UUID, RecipeDto> recipeCache) {
    if (additions == null) {
      return;
    }
    for (Addition a : additions) {
      if (a == null || a.kind() != AdditionKind.SIDE_RECIPE || a.recipeId() == null) {
        continue;
      }
      RecipeDto recipe =
          recipeCache.computeIfAbsent(
              a.recipeId(), id -> recipeQueryService.getById(id).orElse(null));
      if (recipe == null
          || recipe.currentVersionBody() == null
          || recipe.currentVersionBody().ingredients() == null) {
        continue;
      }
      for (IngredientDto ing : recipe.currentVersionBody().ingredients()) {
        if (!ing.optional()) {
          accumulate(demand, ing, BigDecimal.ONE); // one serving of the side
        }
      }
    }
  }

  // ---- Step 2 ---------------------------------------------------------------------------------

  /** All ACTIVE inventory for the user in one round-trip (a single large page). */
  private List<InventoryItemDto> activeInventory(UUID userId) {
    return provisionQueryService
        .listActiveInventory(userId, InventorySearchCriteria.none(), PageRequest.of(0, 5000))
        .getContent();
  }

  private static void subtractInventory(
      Map<String, IngredientDemand> demand, List<InventoryItemDto> inventory) {
    if (inventory == null) {
      return;
    }
    // Sum inventory by normalised key first, then subtract once per key (clamping at zero).
    Map<String, BigDecimal> onHand = new LinkedHashMap<>();
    for (InventoryItemDto item : inventory) {
      String key = IngredientMappingKeys.normalise(item.ingredientMappingKey());
      if (key == null || key.isEmpty() || item.quantity() == null) {
        continue;
      }
      onHand.merge(key, item.quantity(), BigDecimal::add);
    }
    for (Map.Entry<String, BigDecimal> e : onHand.entrySet()) {
      IngredientDemand d = demand.get(e.getKey());
      if (d != null) {
        demand.put(e.getKey(), d.subtractClampingAtZero(e.getValue()));
      }
    }
  }

  // ---- Step 3 + 5 -----------------------------------------------------------------------------

  private ShoppingListLine plannedLine(
      IngredientDemand d,
      Map<String, List<PackSizeHeuristic>> packsByKey,
      GroceryQualityPreferences quality) {
    List<PackSizeHeuristic> packs =
        packsByKey.getOrDefault(d.key(), packSizeHeuristicLookup.resolve(d.key(), d.category()));
    PackSizeOptimiser.PackChoice choice =
        packSizeOptimiser.choose(d, packs, false); // plan demand is non-perishable by default
    return buildLine(d, choice, ShoppingListLineType.PLANNED_DEMAND, quality);
  }

  private ShoppingListLine stapleLine(InventoryItemDto staple, GroceryQualityPreferences quality) {
    String key = IngredientMappingKeys.normalise(staple.ingredientMappingKey());
    BigDecimal qty = staple.quantity() != null ? staple.quantity() : BigDecimal.ONE;
    IngredientDemand d =
        new IngredientDemand(
            key != null ? key : "",
            staple.name(),
            qty,
            staple.unit() != null ? staple.unit() : "items",
            staple.category(),
            null);
    List<PackSizeHeuristic> packs = packSizeHeuristicLookup.resolve(d.key(), d.category());
    PackSizeOptimiser.PackChoice choice = packSizeOptimiser.choose(d, packs, false);
    return buildLine(d, choice, ShoppingListLineType.STAPLE_REPLENISHMENT, quality);
  }

  private ShoppingListLine buildLine(
      IngredientDemand d,
      PackSizeOptimiser.PackChoice choice,
      ShoppingListLineType lineType,
      GroceryQualityPreferences quality) {
    ShoppingListLine line =
        ShoppingListLine.builder()
            .id(UUID.randomUUID())
            .ingredientMappingKey(d.key())
            .displayName(d.displayName() != null ? d.displayName() : d.key())
            .requestedQuantity(d.quantity().setScale(3, RoundingMode.HALF_UP))
            .requestedUnit(d.unit() != null ? d.unit() : "items")
            .lineType(lineType)
            .staleEstimate(false)
            .fulfilmentStatus(LineFulfilmentStatus.UNFILLED)
            .qualityNotes(qualityNote(d.key(), quality))
            .build();
    if (!choice.isEmpty()) {
      line.setSuggestedPackSizeG(choice.packSizeG());
      line.setSuggestedPackCount(choice.packsToBuy());
      line.setSuggestedPackUnit(choice.packUnit());
    }
    return line;
  }

  /** Step 5: a free-text informational hint from the user's grocery-quality preferences. */
  private static String qualityNote(String key, GroceryQualityPreferences quality) {
    if (quality == null) {
      return null;
    }
    List<String> hints = new ArrayList<>();
    if (key != null && key.contains("egg") && nonBlank(quality.freeRangeEggs())) {
      hints.add("free-range eggs: " + quality.freeRangeEggs());
    } else if (isMeatKey(key) && nonBlank(quality.freeRangeMeat())) {
      hints.add("free-range meat: " + quality.freeRangeMeat());
    }
    if (nonBlank(quality.organic())) {
      hints.add("organic: " + quality.organic());
    }
    if (nonBlank(quality.brandedVsOwnLabel())) {
      hints.add("brand: " + quality.brandedVsOwnLabel());
    }
    if (hints.isEmpty()) {
      return null;
    }
    String joined = String.join("; ", hints);
    return joined.length() > 255 ? joined.substring(0, 255) : joined;
  }

  private static boolean isMeatKey(String key) {
    if (key == null) {
      return false;
    }
    return key.contains("chicken")
        || key.contains("beef")
        || key.contains("pork")
        || key.contains("lamb")
        || key.contains("meat");
  }

  // ---- Step 6 ---------------------------------------------------------------------------------

  /**
   * Step-6 cost projection. Alongside the point-estimate total, composes the list-level cost band
   * (grocery-cost-variance ticket): per line, {@code lineMin = unitMin × packCount} and {@code
   * lineMax = unitMax × packCount} where {@code unitMin}/{@code unitMax} come from the aggregate's
   * {@code minPence}/{@code maxPence}, falling back to the point estimate when the aggregate
   * carries no range (e.g. reference fallback). The per-line bounds are clamped around the point
   * estimate ({@code unitMin = min(min, point)}, {@code unitMax = max(max, point)}) so the
   * invariant {@code min ≤ estimatedTotal ≤ max} holds by construction even when reference prices
   * and observations disagree. A line contributes to the band iff it contributes to the total
   * (mirrors {@code estimatedTotalPence} exactly: no aggregate / no point estimate → nothing); no
   * aggregates at all → all three totals null (cold start). Stale aggregates still count into the
   * band — staleness is separately surfaced via {@code staleIngredientCount}.
   */
  private CostProjection projectCost(UUID householdId, List<ShoppingListLine> lines) {
    Set<String> keys = new LinkedHashSet<>();
    for (ShoppingListLine line : lines) {
      keys.add(line.getIngredientMappingKey());
    }
    Map<String, PriceAggregateDto> aggregates =
        keys.isEmpty() ? Map.of() : priceHistoryService.getAggregatesByKeys(householdId, keys);

    int staleCount = 0;
    long totalPence = 0;
    long totalMinPence = 0;
    long totalMaxPence = 0;
    boolean anyEstimate = false;
    BigDecimal weightedConfidenceSum = BigDecimal.ZERO; // sum(confidence * linePence)
    long confidenceWeightTotal = 0; // sum(linePence)

    for (ShoppingListLine line : lines) {
      PriceAggregateDto agg = aggregates.get(line.getIngredientMappingKey());
      if (agg == null || agg.pointEstimatePence() == null) {
        line.setStaleEstimate(true);
        staleCount++;
        continue;
      }
      int packCount = line.getSuggestedPackCount() != null ? line.getSuggestedPackCount() : 1;
      int unitPence = agg.pointEstimatePence();
      int linePence = unitPence * packCount;
      // Band inputs: aggregate min/max when present, the point estimate otherwise; always clamped
      // around the point estimate so min ≤ point ≤ max per line (and therefore per list).
      int unitMin = Math.min(agg.minPence() != null ? agg.minPence() : unitPence, unitPence);
      int unitMax = Math.max(agg.maxPence() != null ? agg.maxPence() : unitPence, unitPence);
      line.setEstimatedUnitPence(unitPence);
      line.setEstimatedLinePence(linePence);
      line.setEstimatedConfidence(agg.confidence());
      line.setStaleEstimate(agg.isStale());
      if (agg.isStale()) {
        staleCount++;
      }
      anyEstimate = true;
      totalPence += linePence;
      totalMinPence += (long) unitMin * packCount;
      totalMaxPence += (long) unitMax * packCount;
      if (agg.confidence() != null && linePence > 0) {
        weightedConfidenceSum =
            weightedConfidenceSum.add(agg.confidence().multiply(BigDecimal.valueOf(linePence)));
        confidenceWeightTotal += linePence;
      }
    }

    if (!anyEstimate) {
      return new CostProjection(null, null, null, null, staleCount); // no aggregates → null totals
    }
    BigDecimal confidence =
        confidenceWeightTotal > 0
            ? weightedConfidenceSum.divide(
                BigDecimal.valueOf(confidenceWeightTotal), 3, RoundingMode.HALF_UP)
            : null;
    return new CostProjection(
        (int) totalPence, (int) totalMinPence, (int) totalMaxPence, confidence, staleCount);
  }

  private record CostProjection(
      Integer totalPence,
      Integer minPence,
      Integer maxPence,
      BigDecimal confidence,
      int staleCount) {}

  // ---- helpers --------------------------------------------------------------------------------

  /** Resolve the household scope: the plan's household, falling back to the user's household. */
  private UUID resolveHouseholdId(UUID userId, PlanDto plan) {
    if (plan.householdId() != null) {
      return plan.householdId();
    }
    return householdQueryService
        .getByUserId(userId)
        .map(HouseholdDto::id)
        .orElse(null); // single-user mode → null household scope per LLD line 94
  }

  private static boolean pantryTrackingEnabled(LifestyleConfigDocument lifestyle) {
    return lifestyle != null
        && lifestyle.pantryTracking() != null
        && lifestyle.pantryTracking().enabled();
  }

  private static boolean nonBlank(String s) {
    return s != null && !s.isBlank();
  }
}
