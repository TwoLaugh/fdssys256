package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic v1 safety gate for accept-flows (LLD lines 1008-1012). Pure code — no AI, no I/O
 * beyond the {@link NutritionTargets} aggregate passed in.
 *
 * <p>Rule 1 (favourite collision) is a no-op in 01e because the soft-preferences shape isn't in the
 * preference module yet. TODO(preference-01c): inject a SoftPreferencesReader and check {@code
 * effective.target()} against the user's taste profile. Rules 2/3/4 ship fully working.
 */
@Component
public class DirectiveSafetyGate {

  private static final Set<String> STAPLES =
      Set.of("water", "salt", "all protein", "all carbs", "all fats");

  private static final BigDecimal UPPER_BOUND_FACTOR = new BigDecimal("1.20");
  private static final BigDecimal LOWER_BOUND_FACTOR = new BigDecimal("0.50");
  private static final BigDecimal MEAL_SUM_TOLERANCE_KCAL = new BigDecimal("100");

  /**
   * Evaluate {@code effective} against {@code currentTargets}. Same input twice → same output.
   * Never mutates either argument.
   */
  public SafetyGateResult evaluate(
      DirectiveInstructionDocument effective,
      HealthDirective directive,
      NutritionTargets currentTargets) {
    List<SafetyFindingDto> findings = new ArrayList<>();

    // Rule 1 — favourite collision: no-op in 01e; pending preference-01c soft-prefs.
    // TODO(preference-01c): read user's taste profile favourites and add WARN findings on
    // collision.

    // Rule 2 — target adjustment bounds
    if (directive.getDirectiveType() == DirectiveType.TARGET_ADJUSTMENT
        && "adjust_target".equals(effective.action())) {
      checkTargetAdjustmentBounds(effective, directive, currentTargets, findings);
    }

    // Rule 3 — macro rebalance bounds
    if (directive.getDirectiveType() == DirectiveType.MACRO_REBALANCE) {
      checkMacroRebalanceBounds(effective, currentTargets, findings);
    }

    // Rule 4 — staple restriction without alternative
    if (directive.getDirectiveType() == DirectiveType.INGREDIENT_RESTRICTION) {
      checkStapleRestriction(effective, findings);
    }

    SafetyGateVerdict verdict = deriveVerdict(findings);
    return new SafetyGateResult(verdict, findings);
  }

  // ---------------- Rule 2 ----------------

  private static void checkTargetAdjustmentBounds(
      DirectiveInstructionDocument effective,
      HealthDirective directive,
      NutritionTargets currentTargets,
      List<SafetyFindingDto> findings) {
    BigDecimal proposedFloor = parseProposedFloor(effective);
    if (proposedFloor == null || currentTargets == null) {
      return;
    }
    BigDecimal dailyTarget = readDailyTarget(currentTargets, directive.getMapsToTier());
    BigDecimal currentFloor = readCurrentFloor(currentTargets, directive.getMapsToTier());

    if (dailyTarget != null
        && dailyTarget.signum() > 0
        && proposedFloor.compareTo(dailyTarget.multiply(UPPER_BOUND_FACTOR)) > 0) {
      findings.add(
          new SafetyFindingDto(
              "target-raise-exceeds-20pct",
              "Proposed floor "
                  + proposedFloor.toPlainString()
                  + " exceeds 1.2x current daily target "
                  + dailyTarget.toPlainString(),
              "BLOCK"));
    }
    if (currentFloor != null
        && currentFloor.signum() > 0
        && proposedFloor.compareTo(currentFloor.multiply(LOWER_BOUND_FACTOR)) < 0) {
      findings.add(
          new SafetyFindingDto(
              "target-lower-below-50pct",
              "Proposed floor "
                  + proposedFloor.toPlainString()
                  + " is below 50% of current floor "
                  + currentFloor.toPlainString(),
              "BLOCK"));
    }
  }

  private static BigDecimal parseProposedFloor(DirectiveInstructionDocument effective) {
    JsonNode node = readExtra(effective, "proposedFloor");
    if (node != null && node.isNumber()) {
      return new BigDecimal(node.asText());
    }
    return null;
  }

  private static BigDecimal readDailyTarget(NutritionTargets t, String tier) {
    if (tier == null) {
      return null;
    }
    return switch (tier) {
      case "protein_floor_g", "protein_target_g" -> t.getProteinTargetG();
      case "carbs_floor_g", "carbs_target_g" -> t.getCarbsTargetG();
      case "fat_floor_g", "fat_target_g" -> t.getFatTargetG();
      case "fibre_floor_g", "fibre_target_g" -> t.getFibreTargetG();
      case "calorie_target", "daily_calorie_target" ->
          BigDecimal.valueOf(t.getDailyCalorieTarget());
      default -> null;
    };
  }

  private static BigDecimal readCurrentFloor(NutritionTargets t, String tier) {
    if (tier == null) {
      return null;
    }
    return switch (tier) {
      case "protein_floor_g" -> t.getProteinFloorG();
      case "carbs_floor_g" -> t.getCarbsFloorG();
      case "fat_floor_g" -> t.getFatFloorG();
      case "fibre_floor_g" -> t.getFibreFloorG();
      default -> null;
    };
  }

  // ---------------- Rule 3 ----------------

  private static void checkMacroRebalanceBounds(
      DirectiveInstructionDocument effective,
      NutritionTargets currentTargets,
      List<SafetyFindingDto> findings) {
    if (currentTargets == null) {
      return;
    }
    BigDecimal dailyTarget = BigDecimal.valueOf(currentTargets.getDailyCalorieTarget());
    BigDecimal mealSum = computePostApplyMealSum(effective, currentTargets);
    if (mealSum == null) {
      return;
    }
    BigDecimal divergence = mealSum.subtract(dailyTarget).abs();
    if (divergence.compareTo(MEAL_SUM_TOLERANCE_KCAL) > 0) {
      findings.add(
          new SafetyFindingDto(
              "meal-sum-divergence-exceeds-100kcal",
              "Post-apply per-meal calorie sum "
                  + mealSum.setScale(0, RoundingMode.HALF_UP).toPlainString()
                  + " diverges from daily target "
                  + dailyTarget.toPlainString()
                  + " by "
                  + divergence.setScale(0, RoundingMode.HALF_UP).toPlainString()
                  + " kcal",
              "BLOCK"));
    }
  }

  private static BigDecimal computePostApplyMealSum(
      DirectiveInstructionDocument effective, NutritionTargets t) {
    // Sum the existing per-meal calorie targets and apply any deltas declared in extras.mealDeltas.
    BigDecimal sum = BigDecimal.ZERO;
    if (t.getPerMealDistribution() != null) {
      for (var entry : t.getPerMealDistribution()) {
        sum = sum.add(BigDecimal.valueOf(entry.getCalorieTarget()));
      }
    }
    JsonNode deltas = readExtra(effective, "mealDeltas");
    if (deltas != null && deltas.isObject()) {
      var fields = deltas.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (field.getValue().isNumber()) {
          sum = sum.add(new BigDecimal(field.getValue().asText()));
        }
      }
    }
    return sum;
  }

  // ---------------- Rule 4 ----------------

  private static void checkStapleRestriction(
      DirectiveInstructionDocument effective, List<SafetyFindingDto> findings) {
    if (effective.target() == null) {
      return;
    }
    String target = effective.target().toLowerCase();
    if (STAPLES.contains(target) && !hasAlternative(effective)) {
      findings.add(
          new SafetyFindingDto(
              "staple-restriction-no-alternative",
              "Restricting a staple ('" + effective.target() + "') without an alternative",
              "BLOCK"));
    }
  }

  private static boolean hasAlternative(DirectiveInstructionDocument effective) {
    JsonNode node = readExtra(effective, "alternative");
    return node != null && !node.isNull() && !node.asText("").isBlank();
  }

  // ---------------- Helpers ----------------

  private static JsonNode readExtra(DirectiveInstructionDocument effective, String key) {
    Map<String, JsonNode> extras = effective.extras();
    if (extras == null) {
      return null;
    }
    return extras.get(key);
  }

  private static SafetyGateVerdict deriveVerdict(List<SafetyFindingDto> findings) {
    if (findings.isEmpty()) {
      return SafetyGateVerdict.PASSED;
    }
    for (SafetyFindingDto f : findings) {
      if ("BLOCK".equals(f.severity())) {
        return SafetyGateVerdict.BLOCKED;
      }
    }
    return SafetyGateVerdict.PASSED_WITH_WARNINGS;
  }
}
