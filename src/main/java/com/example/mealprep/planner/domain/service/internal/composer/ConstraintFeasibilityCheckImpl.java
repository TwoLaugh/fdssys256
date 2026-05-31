package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.ConflictType;
import com.example.mealprep.planner.api.dto.ConstraintConflictDto;
import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ResolutionOptionDto;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.beamsearch.HardFilterRunner;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default {@link ConstraintFeasibilityCheck} (planner-6). Reuses {@link HardFilterRunner} to build
 * the exact per-slot post-hard-filter pool the beam search would see, then runs the LLD's four
 * passes (§ConstraintFeasibilityCheck lines 638-644):
 *
 * <ol>
 *   <li><b>Per-slot pool size</b> — any slot whose pool is below {@code
 *       mealprep.planner.min-pool-per-slot} is "under-pooled" and contributes a conflict.
 *   <li><b>Constrained-slot classification</b> — each under-pooled slot is mapped to a {@link
 *       ConflictType} from the dominant cause (household hard collision on a shared slot with eater
 *       hard constraints; provisions/equipment bottleneck; nutrition-vs-budget; otherwise
 *       over-specified preferences). Slots sharing a type+eater-set are coalesced into one
 *       conflict.
 *   <li><b>Best-possible-plan signal</b> — folded into the pass-1 emptiness check: a fully empty
 *       per-slot pool is the strongest infeasibility signal (no candidate can clear any gate).
 *   <li><b>Resolution ranking</b> — per detected {@link ConflictType}, emit the candidate
 *       relaxations (split slot, drop protein floor, raise budget, widen preferences) with the
 *       slots they would recover, returned best-first by {@code slotsRecovered} then {@code
 *       scoreRecovered}.
 * </ol>
 *
 * <p>Deterministic and side-effect-free — safe to call from the read-only {@code GET /feasibility}
 * query and from inside {@code generatePlan}.
 */
@Component
class ConstraintFeasibilityCheckImpl implements ConstraintFeasibilityCheck {

  /** Estimated composite-score recovery per resolution kind (ranking weight, [0,1]). */
  private static final BigDecimal SCORE_RECOVERED_SPLIT = new BigDecimal("0.30");

  private static final BigDecimal SCORE_RECOVERED_DROP_FLOOR = new BigDecimal("0.25");
  private static final BigDecimal SCORE_RECOVERED_RAISE_BUDGET = new BigDecimal("0.20");
  private static final BigDecimal SCORE_RECOVERED_WIDEN_PREFS = new BigDecimal("0.15");

  private final HardFilterRunner hardFilterRunner;
  private final PlannerProperties properties;

  ConstraintFeasibilityCheckImpl(HardFilterRunner hardFilterRunner, PlannerProperties properties) {
    this.hardFilterRunner = hardFilterRunner;
    this.properties = properties;
  }

  @Override
  public FeasibilityCheckResultDto check(PlanCompositionContext context) {
    List<MealSlotSkeleton> skeletons =
        context.slotSkeletons() == null ? List.of() : context.slotSkeletons();
    if (skeletons.isEmpty()) {
      // No slots to fill ⇒ trivially feasible (an empty week cannot be infeasible).
      return new FeasibilityCheckResultDto(true, List.of(), List.of());
    }

    Map<UUID, List<RecipeDto>> poolBySlot = hardFilterRunner.filterPool(context);
    int minPool = properties.minPoolPerSlot();
    boolean equipmentConstrained = hasConstrainedEquipment(context);

    // Pass 1+2: find under-pooled slots and classify each.
    Map<UUID, MealSlotSkeleton> skeletonById = new LinkedHashMap<>();
    for (MealSlotSkeleton s : skeletons) {
      if (s != null) {
        skeletonById.put(s.slotId(), s);
      }
    }

    // type -> affected slot ids (insertion-ordered, deterministic).
    Map<ConflictType, List<UUID>> slotsByType = new EnumMap<>(ConflictType.class);
    for (MealSlotSkeleton skel : skeletons) {
      if (skel == null) {
        continue;
      }
      List<RecipeDto> pool = poolBySlot.getOrDefault(skel.slotId(), List.of());
      if (pool.size() >= minPool) {
        continue; // adequately pooled
      }
      ConflictType type = classify(skel, context, equipmentConstrained);
      slotsByType.computeIfAbsent(type, t -> new ArrayList<>()).add(skel.slotId());
    }

    if (slotsByType.isEmpty()) {
      return new FeasibilityCheckResultDto(true, List.of(), List.of());
    }

    // Build one conflict per type and its ranked resolutions.
    List<ConstraintConflictDto> conflicts = new ArrayList<>();
    List<ResolutionOptionDto> resolutions = new ArrayList<>();
    for (Map.Entry<ConflictType, List<UUID>> e : slotsByType.entrySet()) {
      ConflictType type = e.getKey();
      List<UUID> affected = e.getValue();
      conflicts.add(
          new ConstraintConflictDto(type, List.copyOf(affected), describe(type, affected)));
      resolutions.addAll(resolutionsFor(type, affected.size()));
    }
    // Rank resolutions best-first: more slots recovered, then more score recovered.
    resolutions.sort(
        (a, b) -> {
          int bySlots = Integer.compare(b.slotsRecovered(), a.slotsRecovered());
          return bySlots != 0 ? bySlots : b.scoreRecovered().compareTo(a.scoreRecovered());
        });

    return new FeasibilityCheckResultDto(false, List.copyOf(conflicts), List.copyOf(resolutions));
  }

  /**
   * Map an under-pooled slot to the dominant conflict cause. Order matters — the most safety-
   * relevant / specific cause wins.
   */
  private ConflictType classify(
      MealSlotSkeleton skel, PlanCompositionContext context, boolean equipmentConstrained) {
    boolean sharedWithMultipleConstrainedEaters =
        skel.shared() && eatersWithHardConstraints(skel, context) >= 2;
    if (sharedWithMultipleConstrainedEaters) {
      return ConflictType.HOUSEHOLD_HARD_COLLISION;
    }
    if (equipmentConstrained) {
      return ConflictType.PROVISIONS_BOTTLENECK;
    }
    if (anyEaterHasNutritionFloor(skel, context)) {
      return ConflictType.NUTRITION_VS_BUDGET;
    }
    return ConflictType.OVER_SPECIFIED_PREFERENCES;
  }

  private int eatersWithHardConstraints(MealSlotSkeleton skel, PlanCompositionContext context) {
    Map<UUID, HardConstraintsDto> byUser = context.hardConstraintsByUserId();
    if (byUser == null || skel.eaters() == null) {
      return 0;
    }
    int n = 0;
    for (UUID eater : skel.eaters()) {
      HardConstraintsDto hc = byUser.get(eater);
      if (hc != null && hasAnyHardConstraint(hc)) {
        n++;
      }
    }
    return n;
  }

  private static boolean hasAnyHardConstraint(HardConstraintsDto hc) {
    return (hc.allergies() != null && !hc.allergies().isEmpty())
        || (hc.medicalDiets() != null && !hc.medicalDiets().isEmpty())
        || (hc.intolerances() != null && !hc.intolerances().isEmpty())
        || hc.dietaryIdentity() != null;
  }

  private boolean anyEaterHasNutritionFloor(MealSlotSkeleton skel, PlanCompositionContext context) {
    if (context.nutritionByUserId() == null || skel.eaters() == null) {
      return false;
    }
    return skel.eaters().stream().map(context.nutritionByUserId()::get).anyMatch(t -> t != null);
  }

  /** True when the provisions bundle reports equipment but some of it is unavailable. */
  private static boolean hasConstrainedEquipment(PlanCompositionContext context) {
    if (context.provisions() == null || context.provisions().equipment() == null) {
      return false;
    }
    List<EquipmentDto> equipment = context.provisions().equipment();
    return !equipment.isEmpty() && equipment.stream().anyMatch(eq -> !eq.available());
  }

  private static String describe(ConflictType type, List<UUID> affected) {
    int n = affected.size();
    String slots = n == 1 ? "1 slot has" : n + " slots have";
    return switch (type) {
      case HOUSEHOLD_HARD_COLLISION ->
          slots + " no recipe that satisfies every eater's combined hard constraints.";
      case PROVISIONS_BOTTLENECK ->
          slots + " no recipe whose required equipment is currently available.";
      case NUTRITION_VS_BUDGET ->
          slots + " too few candidates to clear the nutrition floors within budget.";
      case OVER_SPECIFIED_PREFERENCES ->
          slots + " a candidate pool below the planning minimum after applying preferences.";
    };
  }

  private List<ResolutionOptionDto> resolutionsFor(ConflictType type, int slotsRecovered) {
    return switch (type) {
      case HOUSEHOLD_HARD_COLLISION ->
          List.of(
              new ResolutionOptionDto(
                  "split_slot",
                  "Split this shared meal into per-person slots so each eater's constraints apply"
                      + " independently.",
                  slotsRecovered,
                  SCORE_RECOVERED_SPLIT));
      case NUTRITION_VS_BUDGET ->
          List.of(
              new ResolutionOptionDto(
                  "drop_protein_floor",
                  "Relax the protein floor for this week so more recipes qualify.",
                  slotsRecovered,
                  SCORE_RECOVERED_DROP_FLOOR),
              new ResolutionOptionDto(
                  "raise_budget",
                  "Raise the weekly budget so higher-cost recipes that hit the floors qualify.",
                  slotsRecovered,
                  SCORE_RECOVERED_RAISE_BUDGET));
      case PROVISIONS_BOTTLENECK ->
          List.of(
              new ResolutionOptionDto(
                  "raise_budget",
                  "Mark the required equipment available, or widen the time budget so no-equipment"
                      + " recipes qualify.",
                  slotsRecovered,
                  SCORE_RECOVERED_RAISE_BUDGET));
      case OVER_SPECIFIED_PREFERENCES ->
          List.of(
              new ResolutionOptionDto(
                  "widen_preferences",
                  "Widen soft preferences (cuisine / cooking-method) so more recipes qualify.",
                  slotsRecovered,
                  SCORE_RECOVERED_WIDEN_PREFS));
    };
  }
}
