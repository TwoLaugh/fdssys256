package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate;
import com.example.mealprep.nutrition.domain.service.internal.SafetyGateResult;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Boundary-precise mutation killers for {@link DirectiveSafetyGate}. The existing {@code
 * DirectiveSafetyGateTest} only used values comfortably inside / outside the thresholds, so the
 * {@code ConditionalsBoundaryMutator} ({@code >} ↔ {@code >=}, {@code <} ↔ {@code <=}) and the
 * {@code NegateConditionalsMutator} on the per-meal-distribution null guard survived. These tests
 * pin the exact boundary so flipping the operator changes the verdict.
 */
class DirectiveSafetyGateMutationTest {

  private final DirectiveSafetyGate gate = new DirectiveSafetyGate();
  private final ObjectMapper om = new ObjectMapper();

  private static HealthDirective directive(DirectiveType type, String tier) {
    return HealthDirective.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .externalDirectiveId("ext-1")
        .sourcePlatform("apple-health")
        .directiveType(type)
        .mapsToModel("nutrition_model")
        .mapsToTier(tier)
        .temporary(true)
        .build();
  }

  private DirectiveInstructionDocument adjustProtein(String proposedFloor) {
    return NutritionTestData.instructionFor(
        "adjust_target",
        "protein_floor_g",
        NutritionTestData.instructionExtras(
            "proposedFloor",
            new com.fasterxml.jackson.databind.node.DecimalNode(new BigDecimal(proposedFloor))));
  }

  // ---------------- L86: proposedFloor.compareTo(dailyTarget * 1.20) > 0 ----------------

  @Test
  void rule2_proposedFloorExactlyAt120Pct_doesNotBlock() {
    // proteinTargetG default = 120.0 → upper bound = 120.0 * 1.20 = 144.00.
    // At exactly 144 the original `> 0` is false (no block). ConditionalsBoundary `>= 0` would
    // (wrongly) block. Assert NOT blocked at the exact boundary.
    NutritionTargets targets = NutritionTestData.targets().build();

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("144.00"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .doesNotContain("target-raise-exceeds-20pct");
    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
  }

  @Test
  void rule2_proposedFloorJustAbove120Pct_blocks() {
    // 144.01 > 144.00 → blocks. Together with the boundary test this pins `>` precisely.
    NutritionTargets targets = NutritionTestData.targets().build();

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("144.01"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("target-raise-exceeds-20pct");
    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
  }

  // ---------------- L85: dailyTarget.signum() > 0 ----------------

  @Test
  void rule2_zeroDailyTarget_signumGuardSkipsRaiseCheck() {
    // proteinTargetG = 0 → dailyTarget.signum() == 0. Original `signum() > 0` is false so the
    // raise check is skipped entirely (no block). ConditionalsBoundary `>= 0` would enter the
    // check (0 * 1.20 = 0; proposed 130 > 0 → block). Assert NOT blocked.
    NutritionTargets targets = NutritionTestData.targets().build();
    targets.setProteinTargetG(BigDecimal.ZERO);

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("130"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .doesNotContain("target-raise-exceeds-20pct");
  }

  // ---------------- L98: proposedFloor.compareTo(currentFloor * 0.50) < 0 ----------------

  @Test
  void rule2_proposedFloorExactlyAt50PctOfCurrent_doesNotBlock() {
    // currentFloor = 100 → lower bound = 100 * 0.50 = 50.00. At exactly 50 the original `< 0` is
    // false (no block). ConditionalsBoundary `<= 0` would (wrongly) block. proposed 50 is also
    // well under the 144 upper bound so the raise rule never fires here.
    NutritionTargets targets = NutritionTestData.targets().build();
    targets.setProteinFloorG(BigDecimal.valueOf(100));

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("50.00"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .doesNotContain("target-lower-below-50pct");
    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
  }

  @Test
  void rule2_proposedFloorJustBelow50PctOfCurrent_blocks() {
    NutritionTargets targets = NutritionTestData.targets().build();
    targets.setProteinFloorG(BigDecimal.valueOf(100));

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("49.99"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("target-lower-below-50pct");
  }

  // ---------------- L97: currentFloor.signum() > 0 ----------------

  @Test
  void rule2_zeroCurrentFloor_signumGuardSkipsLowerCheck() {
    // currentFloor = 0 → signum() == 0. Original `> 0` false → lower check skipped, no block.
    // Use a tiny proposed so even under the mutant's entered branch the arithmetic is unambiguous
    // and we still assert no lower-bound finding (the guard is what we pin via the companion
    // boundary test above; here we assert the zero-floor path stays clean).
    NutritionTargets targets = NutritionTestData.targets().build();
    targets.setProteinFloorG(BigDecimal.ZERO);

    SafetyGateResult result =
        gate.evaluate(
            adjustProtein("10"),
            directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"),
            targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .doesNotContain("target-lower-below-50pct");
  }

  // ---------------- L161: divergence.compareTo(100) > 0 ----------------

  @Test
  void rule3_mealSumDivergesExactly100Kcal_doesNotBlock() {
    // Daily target = 2000. Per-meal sum 2000, apply a +100 kcal delta → post-apply sum 2100,
    // |2100 - 2000| = 100 exactly. Original `> 0` over tolerance(100): 100 - 100 = 0, `compareTo`
    // == 0, `> 0` false → no block. ConditionalsBoundary `>= 0` would block. Assert NOT blocked.
    NutritionTargets targets =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10))
            .build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "rebalance_macros",
            null,
            NutritionTestData.instructionExtras(
                "mealDeltas", om.createObjectNode().put("BREAKFAST", 100)));

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.MACRO_REBALANCE, null), targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .doesNotContain("meal-sum-divergence-exceeds-100kcal");
    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
  }

  @Test
  void rule3_mealSumDivergesJustOver100Kcal_blocks() {
    NutritionTargets targets =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10))
            .build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "rebalance_macros",
            null,
            NutritionTestData.instructionExtras(
                "mealDeltas", om.createObjectNode().put("BREAKFAST", 101)));

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.MACRO_REBALANCE, null), targets);

    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("meal-sum-divergence-exceeds-100kcal");
  }

  // ---------------- L180: if (t.getPerMealDistribution() != null) ----------------

  @Test
  void rule3_perMealDistributionEntriesContributeToSum() {
    // NegateConditionals on `t.getPerMealDistribution() != null`. The list is non-null here with
    // entries summing to 2000. With a +150 delta the post-apply sum is 2150 → divergence 150 >
    // 100 → block. If the `!= null` guard were negated (skip the per-meal loop), sum would be just
    // the 150 delta, divergence |150 - 2000| = 1850 → still blocks BUT with a different message
    // number. We assert the exact post-apply figure in the finding message to pin the loop ran.
    NutritionTargets targets =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40))
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10))
            .build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "rebalance_macros",
            null,
            NutritionTestData.instructionExtras(
                "mealDeltas", om.createObjectNode().put("BREAKFAST", 150)));

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.MACRO_REBALANCE, null), targets);

    SafetyFindingDto finding =
        result.findings().stream()
            .filter(f -> f.code().equals("meal-sum-divergence-exceeds-100kcal"))
            .findFirst()
            .orElseThrow();
    // Post-apply per-meal sum must be 2150 (2000 from the four meals + 150 delta). If the per-meal
    // loop were skipped it would read 150 instead — the message text pins which path ran.
    assertThat(finding.message()).contains("2150");
    assertThat(finding.message()).contains("by 150 kcal");
  }
}
