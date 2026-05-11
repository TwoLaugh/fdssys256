package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate;
import com.example.mealprep.nutrition.domain.service.internal.SafetyGateResult;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic {@link DirectiveSafetyGate}. Same input twice → same output. */
class DirectiveSafetyGateTest {

  private final DirectiveSafetyGate gate = new DirectiveSafetyGate();

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

  // ---------------- Rule 2 — target adjustment bounds ----------------

  @Test
  void rule2_proposedFloorExceeds120Pct_blocks() {
    NutritionTargets targets = NutritionTestData.targets().build(); // protein target 120, no floor
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "adjust_target",
            "protein_floor_g",
            NutritionTestData.instructionExtras("proposedFloor", new IntNode(200)));

    SafetyGateResult result =
        gate.evaluate(
            instruction, directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("target-raise-exceeds-20pct");
  }

  @Test
  void rule2_proposedFloorBelow50PctOfCurrent_blocks() {
    NutritionTargets targets = NutritionTestData.targets().build();
    targets.setProteinFloorG(BigDecimal.valueOf(100));
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "adjust_target",
            "protein_floor_g",
            NutritionTestData.instructionExtras("proposedFloor", new IntNode(40)));

    SafetyGateResult result =
        gate.evaluate(
            instruction, directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("target-lower-below-50pct");
  }

  @Test
  void rule2_proposedFloorWithinBounds_passes() {
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "adjust_target",
            "protein_floor_g",
            NutritionTestData.instructionExtras("proposedFloor", new IntNode(130)));

    SafetyGateResult result =
        gate.evaluate(
            instruction, directive(DirectiveType.TARGET_ADJUSTMENT, "protein_floor_g"), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
    assertThat(result.findings()).isEmpty();
  }

  // ---------------- Rule 3 — macro rebalance ----------------

  @Test
  void rule3_macroRebalanceDivergesBy150Kcal_blocks() {
    NutritionTargets targets =
        NutritionTestData.targets()
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.BREAKFAST,
                500,
                BigDecimal.valueOf(30))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
                600,
                BigDecimal.valueOf(40))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.DINNER,
                700,
                BigDecimal.valueOf(40))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.SNACKS,
                200,
                BigDecimal.valueOf(10))
            .build();
    // Daily target is 2000; per-meal sum is also 2000 — apply a +150 kcal delta to BREAKFAST.
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "rebalance_macros",
            null,
            NutritionTestData.instructionExtras(
                "mealDeltas",
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode()
                    .put("BREAKFAST", 150)));

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.MACRO_REBALANCE, null), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("meal-sum-divergence-exceeds-100kcal");
  }

  // ---------------- Rule 4 — staple restriction ----------------

  @Test
  void rule4_stapleRestrictionWithoutAlternative_blocks() {
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor("restrict_ingredient", "water", null);

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
    assertThat(result.findings())
        .extracting(SafetyFindingDto::code)
        .contains("staple-restriction-no-alternative");
  }

  @Test
  void rule4_stapleRestrictionWithAlternative_passes() {
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "restrict_ingredient",
            "water",
            NutritionTestData.staplesAlternativeExtras("herbal tea"));

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
  }

  @Test
  void rule4_nonStapleRestriction_passes() {
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor("restrict_ingredient", "shellfish", null);

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);

    assertThat(result.verdict()).isEqualTo(SafetyGateVerdict.PASSED);
  }

  // ---------------- Rule 1 — favourite collision (no-op in 01e) ----------------

  @Test
  void rule1_favouriteCollision_currentlyNoOp() {
    // TODO(preference-01c): once soft-preferences are available, this test should assert a WARN
    //  finding with code "favourite-collision". For now, the gate is a no-op for rule 1.
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor("restrict_ingredient", "chicken", null);

    SafetyGateResult result =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);

    // No findings about favourites — rule 1 returns nothing in 01e.
    assertThat(result.findings()).noneMatch(f -> f.code().contains("favourite"));
  }

  @Test
  void deterministic_sameInputTwice_sameOutput() {
    NutritionTargets targets = NutritionTestData.targets().build();
    DirectiveInstructionDocument instruction =
        NutritionTestData.instructionFor(
            "restrict_ingredient", "water", NutritionTestData.staplesAlternativeExtras("oat milk"));

    SafetyGateResult a =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);
    SafetyGateResult b =
        gate.evaluate(instruction, directive(DirectiveType.INGREDIENT_RESTRICTION, null), targets);

    assertThat(a).isEqualTo(b);
  }

  // Wire up an `unused` import to avoid the unused-import warning on TextNode.
  @SuppressWarnings("unused")
  private static final TextNode SUPPRESS_UNUSED = new TextNode("");
}
