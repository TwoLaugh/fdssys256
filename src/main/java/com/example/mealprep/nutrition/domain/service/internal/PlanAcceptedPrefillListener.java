package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.event.PlanAcceptedEvent;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wires plan acceptance into intake pre-fill (D-0008): the production caller the {@code
 * prefillFromPlan} seam waited for. Listens {@code AFTER_COMMIT} like the other plan-event
 * consumers (grocery {@code ShoppingListRecalcListener}, notification {@code
 * PlannerEventListener}), fetches the accepted plan, and pre-fills each eater's intake days for the
 * plan's week.
 *
 * <p>Slot mapping follows the pinned CUSTOM/SNACK join rule (lld/nutrition.md, Flow 5 note):
 * breakfast / lunch / dinner planner slots become intake slots; SNACK contributions join the day's
 * snacks bucket via the snack log, CUSTOM slots have no intake counterpart, so neither is
 * pre-filled here.
 *
 * <p>Planned figures are per person: the scheduled recipe's per-serving nutrition times the slot's
 * portion factor, plus Phase-2 additions verbatim (the same arithmetic as the planner's {@code
 * DailyMacroAggregator}; deliberately NOT scaled by the household head-count). Macros round to the
 * slot columns' scale (1dp), micros to 3dp. A recipe without computed nutrition contributes
 * nothing: planned fields stay null and micros stay absent, never zero-filled (Flow 9 measurement
 * honesty).
 *
 * <p>Idempotent end to end: {@code prefillFromPlan} preserves decided slots and updates PENDING
 * ones in place, so re-accepting or re-optimising never duplicates slots or clobbers user-entered
 * actuals.
 *
 * <p>No listener-level transaction: the publisher's transaction is already committed and every
 * read/write here goes through a {@code @Transactional} service proxy, so each (eater, day)
 * pre-fill runs in its own transaction and one failure never rolls back the others. Nothing is ever
 * re-thrown.
 */
@Component
public class PlanAcceptedPrefillListener {

  private static final Logger log = LoggerFactory.getLogger(PlanAcceptedPrefillListener.class);

  private static final int MACRO_SCALE = 1;
  private static final int MICRO_SCALE = 3;

  private final PlanQueryService planQueryService;
  private final RecipeQueryService recipeQueryService;
  private final NutritionUpdateService nutritionUpdateService;
  private final ObjectMapper objectMapper;

  public PlanAcceptedPrefillListener(
      PlanQueryService planQueryService,
      RecipeQueryService recipeQueryService,
      NutritionUpdateService nutritionUpdateService,
      ObjectMapper objectMapper) {
    this.planQueryService = planQueryService;
    this.recipeQueryService = recipeQueryService;
    this.nutritionUpdateService = nutritionUpdateService;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPlanAccepted(PlanAcceptedEvent event) {
    PlanDto plan;
    try {
      plan = planQueryService.getPlanById(event.planId()).orElse(null);
    } catch (RuntimeException ex) {
      log.warn("prefill: plan lookup failed for {}: {}", event.planId(), ex.toString(), ex);
      return;
    }
    if (plan == null || plan.days() == null) {
      log.warn("prefill: accepted plan {} not found or dayless; skipping", event.planId());
      return;
    }

    // (eater, date) -> meal slot -> planned input. First slot of a meal kind wins per day.
    Map<UUID, Map<LocalDate, Map<MealSlot, PlannedSlotInputDto>>> perEater = new LinkedHashMap<>();
    Map<UUID, Optional<NutritionPerServingDto>> nutritionCache = new LinkedHashMap<>();
    try {
      for (DayDto day : plan.days()) {
        if (day == null || day.date() == null || day.slots() == null) {
          continue;
        }
        for (MealSlotDto slot : day.slots()) {
          MealSlot mealSlot = slot == null ? null : intakeSlotFor(slot.kind());
          if (mealSlot == null || slot.eaters() == null) {
            continue;
          }
          PlannedSlotInputDto input =
              plannedInput(mealSlot, slot.scheduledRecipe(), nutritionCache);
          for (UUID eater : slot.eaters()) {
            if (eater == null) {
              continue;
            }
            perEater
                .computeIfAbsent(eater, e -> new LinkedHashMap<>())
                .computeIfAbsent(day.date(), d -> new EnumMap<>(MealSlot.class))
                .putIfAbsent(mealSlot, input);
          }
        }
      }
    } catch (RuntimeException ex) {
      log.warn("prefill: assembling inputs failed for plan {}: {}", plan.id(), ex.toString(), ex);
      return;
    }

    for (Map.Entry<UUID, Map<LocalDate, Map<MealSlot, PlannedSlotInputDto>>> eater :
        perEater.entrySet()) {
      for (Map.Entry<LocalDate, Map<MealSlot, PlannedSlotInputDto>> day :
          eater.getValue().entrySet()) {
        try {
          nutritionUpdateService.prefillFromPlan(
              eater.getKey(), day.getKey(), plan.id(), List.copyOf(day.getValue().values()));
        } catch (RuntimeException ex) {
          // Own transaction per call; skip this day and keep pre-filling the rest.
          log.warn(
              "prefill failed for user={} date={} plan={}: {}",
              eater.getKey(),
              day.getKey(),
              plan.id(),
              ex.toString(),
              ex);
        }
      }
    }
    log.info("prefill dispatched for plan={} eaters={}", plan.id(), perEater.size());
  }

  /** Breakfast/lunch/dinner map 1:1; SNACK and CUSTOM have no per-slot intake counterpart. */
  private static MealSlot intakeSlotFor(SlotKind kind) {
    if (kind == null) {
      return null;
    }
    return switch (kind) {
      case BREAKFAST -> MealSlot.BREAKFAST;
      case LUNCH -> MealSlot.LUNCH;
      case DINNER -> MealSlot.DINNER;
      case SNACK, CUSTOM -> null;
    };
  }

  /**
   * One slot's planned figures: per-serving recipe nutrition scaled by the portion factor, plus
   * additions verbatim (pre-sized, not scaled). Fields stay null until a source contributes, so a
   * slot with no scheduled recipe or no computed nutrition pre-fills with null planned values and
   * no micros document.
   */
  private PlannedSlotInputDto plannedInput(
      MealSlot mealSlot,
      ScheduledRecipeDto scheduled,
      Map<UUID, Optional<NutritionPerServingDto>> cache) {
    UUID recipeId = scheduled == null ? null : scheduled.recipeId();
    NutritionAccumulator acc = new NutritionAccumulator();
    if (recipeId != null) {
      NutritionPerServingDto perServing =
          cache
              .computeIfAbsent(
                  recipeId,
                  id ->
                      recipeQueryService
                          .getById(id)
                          .map(RecipeDto::currentVersionBody)
                          .map(RecipeVersionDto::nutritionPerServing))
              .orElse(null);
      if (perServing != null) {
        BigDecimal pf =
            scheduled.portionFactor() == null ? BigDecimal.ONE : scheduled.portionFactor();
        acc.add(perServing, pf);
      }
    }
    if (scheduled != null && scheduled.additions() != null) {
      for (Addition addition : scheduled.additions()) {
        if (addition != null && addition.nutrition() != null) {
          acc.add(addition.nutrition(), BigDecimal.ONE);
        }
      }
    }
    return new PlannedSlotInputDto(
        mealSlot,
        recipeId,
        acc.calories(),
        scale(acc.protein, MACRO_SCALE),
        scale(acc.carbs, MACRO_SCALE),
        scale(acc.fat, MACRO_SCALE),
        scale(acc.fibre, MACRO_SCALE),
        acc.microsNode(objectMapper));
  }

  private static BigDecimal scale(BigDecimal v, int scale) {
    return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
  }

  /** Null-until-contribution sums, mirroring DailyMacroAggregator's null-safe walk. */
  private static final class NutritionAccumulator {
    private BigDecimal calories;
    private BigDecimal protein;
    private BigDecimal carbs;
    private BigDecimal fat;
    private BigDecimal fibre;
    private final Map<String, BigDecimal> micros = new LinkedHashMap<>();

    void add(NutritionPerServingDto n, BigDecimal factor) {
      calories = addTo(calories, BigDecimal.valueOf(n.calories()), factor);
      protein = addTo(protein, n.proteinG(), factor);
      carbs = addTo(carbs, n.carbsG(), factor);
      fat = addTo(fat, n.fatG(), factor);
      fibre = addTo(fibre, n.fibreG(), factor);
      if (n.micros() != null) {
        for (Map.Entry<String, BigDecimal> e : n.micros().entrySet()) {
          if (e.getKey() != null && e.getValue() != null) {
            micros.merge(e.getKey(), e.getValue().multiply(factor), BigDecimal::add);
          }
        }
      }
    }

    private static BigDecimal addTo(BigDecimal acc, BigDecimal value, BigDecimal factor) {
      if (value == null) {
        return acc;
      }
      BigDecimal contribution = value.multiply(factor);
      return acc == null ? contribution : acc.add(contribution);
    }

    Integer calories() {
      return calories == null ? null : calories.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Measured keys only, 3dp; null when nothing contributed (absent stays absent). */
    JsonNode microsNode(ObjectMapper mapper) {
      if (micros.isEmpty()) {
        return null;
      }
      ObjectNode node = mapper.createObjectNode();
      for (Map.Entry<String, BigDecimal> e : micros.entrySet()) {
        node.put(e.getKey(), e.getValue().setScale(MICRO_SCALE, RoundingMode.HALF_UP));
      }
      return node;
    }
  }
}
