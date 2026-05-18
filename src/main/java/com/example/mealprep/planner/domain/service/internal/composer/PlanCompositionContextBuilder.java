package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.PlannerSlotEntryDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptContextBuilder;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.domain.service.ProvisionForPlannerService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the read-only {@link PlanCompositionContext} consumed by Stage A&rarr;D (planner-01j).
 * Fans out — exactly once per generation — to the cross-module read surfaces that exist today
 * (preference hard-constraints, nutrition targets, household settings + slot-configuration,
 * provisions planner-bundle) and pins a frozen {@link RecipePoolSnapshot} via {@link
 * RecipePoolSource}.
 *
 * <p><b>LLD reconciliation.</b> The 01j ticket predicted a 13-field context fed from a set of
 * {@code <Module>ForPlannerBundleDto}s; the codebase that merged through 01d-01i instead locked
 * {@link PlanCompositionContext} to the 13-field shape it ships and the read surfaces that actually
 * exist ({@code PreferenceQueryService.getHardConstraints}, {@code
 * NutritionQueryService.getTargets}, {@code HouseholdQueryService.getSettings /
 * getSlotConfigurationPlannerView}, {@code ProvisionForPlannerService.getBundle}). Surfaces the LLD
 * named but that do not exist yet (a soft-preference bundle, a household-merge service, a
 * catalogue-wide recipe search) are degraded to {@code null} / empty per {@link
 * PlanCompositionContext}'s own javadoc convention — the search algorithm and gates already
 * null-tolerate these.
 *
 * <p>Also the production {@link ReoptContextBuilder} the {@code MidWeekReoptCoordinator}
 * (planner-01i) waits on: {@link #buildForReopt} narrows the context to the non-pinned slots.
 *
 * <p>This is the SOLE production {@code ReoptContextBuilder} (no {@code @Primary} needed: in the
 * running app it is the only bean of the type). The planner-01i/01k ITs that predate it supply a
 * test-scoped stand-in and mark THAT {@code @Primary} so their algorithm-deterministic assertions
 * win in-test (round-6 retro: the test-side stand-in carries {@code @Primary}, not the prod impl —
 * a prod {@code @Primary} would collide with the already-{@code @Primary} stand-in: "more than one
 * primary bean").
 */
@Component
public class PlanCompositionContextBuilder implements ReoptContextBuilder {

  private static final Logger log = LoggerFactory.getLogger(PlanCompositionContextBuilder.class);

  private final HouseholdQueryService householdQueryService;
  private final PreferenceQueryService preferenceQueryService;
  private final NutritionQueryService nutritionQueryService;
  private final ProvisionForPlannerService provisionForPlannerService;
  private final RecipePoolSource recipePoolSource;
  private final Clock clock;

  PlanCompositionContextBuilder(
      HouseholdQueryService householdQueryService,
      PreferenceQueryService preferenceQueryService,
      NutritionQueryService nutritionQueryService,
      ProvisionForPlannerService provisionForPlannerService,
      RecipePoolSource recipePoolSource,
      Clock clock) {
    this.householdQueryService = householdQueryService;
    this.preferenceQueryService = preferenceQueryService;
    this.nutritionQueryService = nutritionQueryService;
    this.provisionForPlannerService = provisionForPlannerService;
    this.recipePoolSource = recipePoolSource;
    this.clock = clock;
  }

  /**
   * Assemble the full composition context for a fresh generation.
   *
   * @param request the generate request (household + week)
   * @param requestUserId the resolved caller user id (server-side; never client-supplied)
   * @param traceId the run trace id (generated by the composer at entry)
   * @param decisionId the composer-entry decision-log id (may be {@code null} until planner-01l)
   */
  public PlanCompositionContext build(
      GeneratePlanRequest request, UUID requestUserId, UUID traceId, UUID decisionId) {
    UUID householdId = request.householdId();

    SlotConfigurationPlannerViewDto slotConfig =
        householdQueryService.getSlotConfigurationPlannerView(householdId);

    List<MealSlotSkeleton> skeletons = buildSkeletons(slotConfig, request.weekStartDate());

    List<UUID> memberUserIds = resolveMembers(slotConfig, requestUserId);

    Map<UUID, HardConstraintsDto> hardConstraintsByUserId = new HashMap<>();
    Map<UUID, TargetsDto> nutritionByUserId = new HashMap<>();
    for (UUID userId : memberUserIds) {
      preferenceQueryService
          .getHardConstraints(userId)
          .ifPresent(hc -> hardConstraintsByUserId.put(userId, hc));
      nutritionQueryService.getTargets(userId).ifPresent(t -> nutritionByUserId.put(userId, t));
    }

    HouseholdSettingsDto householdSettings =
        householdQueryService.getSettings(householdId, requestUserId).orElse(null);

    ProvisionForPlannerBundleDto provisions = provisionForPlannerService.getBundle(requestUserId);

    List<RecipeDto> pool = recipePoolSource.fetchPool(householdId, skeletons, traceId);
    if (pool.isEmpty()) {
      log.warn(
          "Recipe pool empty for household={} week={} (no recipe-search surface wired); Stage A"
              + " will produce no candidates and the composer will persist a quality-warning plan.",
          householdId,
          request.weekStartDate());
    }

    return new PlanCompositionContext(
        householdId,
        request.weekStartDate(),
        skeletons,
        hardConstraintsByUserId,
        Map.of(), // soft-preference bundle: no cross-module surface exists yet (LLD reconciliation)
        null, // merged household prefs: ditto
        provisions,
        householdSettings,
        new RecipePoolSnapshot(pool, clock.instant()),
        List.of(), // no pinned assignments for a fresh generation
        traceId,
        decisionId,
        nutritionByUserId);
  }

  @Override
  public PlanCompositionContext buildForReopt(
      Plan activePlan,
      List<MealSlot> nonPinnedSlots,
      List<SlotAssignment> pinnedAssignments,
      UUID traceId) {
    UUID householdId = activePlan.getHouseholdId();

    List<MealSlotSkeleton> skeletons = new ArrayList<>(nonPinnedSlots.size());
    for (MealSlot slot : nonPinnedSlots) {
      skeletons.add(
          new MealSlotSkeleton(
              slot.getDay().getId(),
              slot.getId(),
              slot.getSlotIndex(),
              slot.getDay().getOnDate(),
              slot.getKind(),
              slot.getLabel(),
              slot.getTimeBudgetMin(),
              slot.isShared(),
              new ArrayList<>(slot.getEaters())));
    }

    Map<UUID, HardConstraintsDto> hardConstraintsByUserId = new HashMap<>();
    Map<UUID, TargetsDto> nutritionByUserId = new HashMap<>();
    for (MealSlot slot : nonPinnedSlots) {
      for (UUID eater : slot.getEaters()) {
        hardConstraintsByUserId.computeIfAbsent(
            eater, u -> preferenceQueryService.getHardConstraints(u).orElse(null));
        nutritionByUserId.computeIfAbsent(
            eater, u -> nutritionQueryService.getTargets(u).orElse(null));
      }
    }
    hardConstraintsByUserId.values().removeIf(java.util.Objects::isNull);
    nutritionByUserId.values().removeIf(java.util.Objects::isNull);

    HouseholdSettingsDto householdSettings = null;
    ProvisionForPlannerBundleDto provisions = null;
    List<RecipeDto> pool = recipePoolSource.fetchPool(householdId, skeletons, traceId);
    if (pool.isEmpty()) {
      log.warn(
          "Re-opt recipe pool empty for plan={} (no recipe-search surface wired); re-opt will"
              + " yield no material diff.",
          activePlan.getId());
    }

    return new PlanCompositionContext(
        householdId,
        activePlan.getWeekStartDate(),
        skeletons,
        hardConstraintsByUserId,
        Map.of(),
        null,
        provisions,
        householdSettings,
        new RecipePoolSnapshot(pool, clock.instant()),
        pinnedAssignments,
        traceId,
        UUID.randomUUID(),
        nutritionByUserId);
  }

  private List<MealSlotSkeleton> buildSkeletons(
      SlotConfigurationPlannerViewDto slotConfig, java.time.LocalDate weekStartDate) {
    List<MealSlotSkeleton> skeletons = new ArrayList<>();
    if (slotConfig == null || slotConfig.slots() == null || slotConfig.slots().isEmpty()) {
      return skeletons;
    }
    // One row per (day-of-week, configured slot) across the 7-day week. slotIndex is the
    // configured slot's ordinal within the day.
    for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
      java.time.LocalDate onDate = weekStartDate.plusDays(dayOffset);
      int slotIndex = 0;
      for (PlannerSlotEntryDto entry : slotConfig.slots()) {
        List<UUID> eaters =
            entry.eaterUserIdsIfPerPerson() == null
                ? new ArrayList<>(
                    slotConfig.allEaterUserIds() == null ? List.of() : slotConfig.allEaterUserIds())
                : new ArrayList<>(entry.eaterUserIdsIfPerPerson());
        skeletons.add(
            new MealSlotSkeleton(
                UUID.randomUUID(),
                UUID.randomUUID(),
                slotIndex++,
                onDate,
                mapKind(entry.kind()),
                entry.slotKey(),
                entry.timeBudgetMin(),
                entry.shared(),
                eaters));
      }
    }
    return skeletons;
  }

  private List<UUID> resolveMembers(
      SlotConfigurationPlannerViewDto slotConfig, UUID fallbackUserId) {
    if (slotConfig != null
        && slotConfig.allEaterUserIds() != null
        && !slotConfig.allEaterUserIds().isEmpty()) {
      return slotConfig.allEaterUserIds();
    }
    return List.of(fallbackUserId);
  }

  private SlotKind mapKind(com.example.mealprep.household.domain.entity.SlotKind kind) {
    if (kind == null) {
      return SlotKind.DINNER;
    }
    return switch (kind) {
      case breakfast -> SlotKind.BREAKFAST;
      case lunch -> SlotKind.LUNCH;
      case dinner -> SlotKind.DINNER;
      case snack -> SlotKind.SNACK;
      case custom -> SlotKind.CUSTOM;
    };
  }
}
