package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Resolves a feedback-adjustment {@code target} string to a read/write handle on one field of a
 * {@link NutritionTargets} aggregate (nutrition-01i). The allow-set is explicit; an unknown {@code
 * target} throws {@link InvalidFeedbackAdjustmentException} (422 → bridge books FAILED).
 *
 * <p>Resolution categories (per the ticket §5):
 *
 * <ul>
 *   <li>{@code calorie_target} → the daily calorie target (read as a {@code BigDecimal}, written
 *       back as a rounded {@code int}).
 *   <li>{@code protein_target_g} / {@code carbs_target_g} / {@code fat_target_g} / {@code
 *       fibre_target_g} / {@code sat_fat_target_g} → the matching macro {@code targetG}.
 *   <li>{@code micro.<nutrient_key>} → an EXISTING {@link MicroTarget} row's {@code targetValue}. A
 *       missing row resolves to {@link Optional#empty()} — the caller no-ops with WARN (the user
 *       has not opted into that micro; feedback does not create it).
 *   <li>{@code per_meal.<slot>.calorie_target} / {@code per_meal.<slot>.protein_target_g} → the
 *       {@link PerMealDistributionEntry} for the slot. A missing slot row is a hard {@link
 *       InvalidFeedbackAdjustmentException} (the slot must already be configured).
 * </ul>
 *
 * <p>The returned {@link ResolvedTarget#auditFieldPath()} is the dotted identifier persisted on the
 * audit row and carried on {@code NutritionTargetsChangedEvent} (e.g. {@code "protein_target_g"},
 * {@code "micro.sodium_mg.target"}, {@code "per_meal.lunch.calorie_target"}).
 */
@Component
public class FeedbackTargetResolver {

  static final String CALORIE_TARGET = "calorie_target";
  static final String PROTEIN_TARGET_G = "protein_target_g";
  static final String CARBS_TARGET_G = "carbs_target_g";
  static final String FAT_TARGET_G = "fat_target_g";
  static final String FIBRE_TARGET_G = "fibre_target_g";
  static final String SAT_FAT_TARGET_G = "sat_fat_target_g";
  static final String MICRO_PREFIX = "micro.";
  static final String PER_MEAL_PREFIX = "per_meal.";

  /**
   * Resolve {@code target} against {@code aggregate}. Returns {@link Optional#empty()} only for the
   * "micro target row not opted-in" no-op case; every other unresolved target throws {@link
   * InvalidFeedbackAdjustmentException}.
   */
  public Optional<ResolvedTarget> resolve(NutritionTargets aggregate, String target) {
    if (target == null || target.isBlank()) {
      throw new InvalidFeedbackAdjustmentException(target);
    }
    return switch (target) {
      case CALORIE_TARGET ->
          Optional.of(
              new ResolvedTarget(
                  "calorie_target",
                  () -> BigDecimal.valueOf(aggregate.getDailyCalorieTarget()),
                  value -> aggregate.setDailyCalorieTarget(roundToInt(value)),
                  true));
      case PROTEIN_TARGET_G ->
          Optional.of(
              macro(
                  "protein_target_g", aggregate::getProteinTargetG, aggregate::setProteinTargetG));
      case CARBS_TARGET_G ->
          Optional.of(
              macro("carbs_target_g", aggregate::getCarbsTargetG, aggregate::setCarbsTargetG));
      case FAT_TARGET_G ->
          Optional.of(macro("fat_target_g", aggregate::getFatTargetG, aggregate::setFatTargetG));
      case FIBRE_TARGET_G ->
          Optional.of(
              macro("fibre_target_g", aggregate::getFibreTargetG, aggregate::setFibreTargetG));
      case SAT_FAT_TARGET_G ->
          Optional.of(
              macro("sat_fat_target_g", aggregate::getSatFatTargetG, aggregate::setSatFatTargetG));
      default -> resolveCompound(aggregate, target);
    };
  }

  private Optional<ResolvedTarget> resolveCompound(NutritionTargets aggregate, String target) {
    if (target.startsWith(MICRO_PREFIX)) {
      return resolveMicro(aggregate, target);
    }
    if (target.startsWith(PER_MEAL_PREFIX)) {
      return Optional.of(resolvePerMeal(aggregate, target));
    }
    throw new InvalidFeedbackAdjustmentException(target);
  }

  private Optional<ResolvedTarget> resolveMicro(NutritionTargets aggregate, String target) {
    String nutrientKey = target.substring(MICRO_PREFIX.length());
    if (nutrientKey.isBlank()) {
      throw new InvalidFeedbackAdjustmentException(target);
    }
    Optional<MicroTarget> row =
        aggregate.getMicroTargets().stream()
            .filter(m -> nutrientKey.equals(m.getNutrientKey()))
            .findFirst();
    // Absent micro row → empty (no-op WARN at the caller). Do NOT create from feedback.
    return row.map(
        m ->
            new ResolvedTarget(
                "micro." + nutrientKey + ".target", m::getTargetValue, m::setTargetValue, false));
  }

  private ResolvedTarget resolvePerMeal(NutritionTargets aggregate, String target) {
    // per_meal.<slot>.<field>
    String rest = target.substring(PER_MEAL_PREFIX.length());
    int dot = rest.indexOf('.');
    if (dot <= 0 || dot == rest.length() - 1) {
      throw new InvalidFeedbackAdjustmentException(target);
    }
    String slotToken = rest.substring(0, dot);
    String field = rest.substring(dot + 1);
    MealSlot slot;
    try {
      slot = MealSlot.valueOf(slotToken.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknownSlot) {
      throw new InvalidFeedbackAdjustmentException(target);
    }
    PerMealDistributionEntry entry =
        aggregate.getPerMealDistribution().stream()
            .filter(e -> e.getMealSlot() == slot)
            .findFirst()
            .orElseThrow(() -> new InvalidFeedbackAdjustmentException(target));
    return switch (field) {
      case "calorie_target" ->
          new ResolvedTarget(
              "per_meal." + slotToken.toLowerCase(java.util.Locale.ROOT) + ".calorie_target",
              () -> BigDecimal.valueOf(entry.getCalorieTarget()),
              value -> entry.setCalorieTarget(roundToInt(value)),
              true);
      case "protein_target_g" ->
          new ResolvedTarget(
              "per_meal." + slotToken.toLowerCase(java.util.Locale.ROOT) + ".protein_target_g",
              entry::getProteinTargetG,
              entry::setProteinTargetG,
              false);
      default -> throw new InvalidFeedbackAdjustmentException(target);
    };
  }

  private static ResolvedTarget macro(
      String auditFieldPath, Supplier<BigDecimal> getter, Consumer<BigDecimal> setter) {
    return new ResolvedTarget(auditFieldPath, getter, setter, false);
  }

  private static int roundToInt(BigDecimal value) {
    return value.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
  }

  /**
   * A resolved read/write handle on a single target field. {@code integerValued} marks the
   * calorie-style fields stored as {@code int} (rounded on write); macro / micro / per-meal protein
   * fields are {@code BigDecimal}-valued.
   */
  public record ResolvedTarget(
      String auditFieldPath,
      Supplier<BigDecimal> getter,
      Consumer<BigDecimal> setter,
      boolean integerValued) {

    public BigDecimal currentValue() {
      return getter.get();
    }

    public void apply(BigDecimal newValue) {
      setter.accept(newValue);
    }
  }
}
