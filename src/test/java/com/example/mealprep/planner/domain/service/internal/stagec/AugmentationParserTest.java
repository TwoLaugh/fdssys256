package com.example.mealprep.planner.domain.service.internal.stagec;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.AugmentationProposal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AugmentationParser}. Lives in the {@code stagec} package because the parser
 * and the sealed {@code Augmentation} hierarchy are module-internal (the test mirrors the {@code
 * beamsearch}/{@code scoring} internal-test convention). Covers ticket planner-01h §"Parser edge
 * cases": the silent-drop policy (returns {@code null}, never throws) on unknown type / missing
 * required fields, and case-insensitive {@code type} matching.
 */
class AugmentationParserTest {

  private final AugmentationParser parser = new AugmentationParser();

  private static AugmentationProposal proposal(
      String type,
      UUID slot,
      UUID recipeId,
      Integer servings,
      String fromKey,
      String toKey,
      String issue,
      String resolution) {
    return new AugmentationProposal(
        type, slot, recipeId, servings, fromKey, toKey, issue, resolution, "because");
  }

  @Test
  void parse_addSnackWithAllRequiredFields_returnsTypedAddSnack() {
    UUID slot = UUID.randomUUID();
    UUID recipe = UUID.randomUUID();

    Augmentation a = parser.parse(proposal("ADD_SNACK", slot, recipe, 2, null, null, null, null));

    assertThat(a).isInstanceOf(AddSnackAugmentation.class);
    AddSnackAugmentation snack = (AddSnackAugmentation) a;
    assertThat(snack.targetSlotId()).isEqualTo(slot);
    assertThat(snack.newRecipeId()).isEqualTo(recipe);
    assertThat(snack.servings()).isEqualTo(2);
    assertThat(snack.reasoning()).isEqualTo("because");
  }

  @Test
  void parse_addSnackLowercaseType_parsedViaToUpperCase() {
    Augmentation a =
        parser.parse(
            proposal("add_snack", UUID.randomUUID(), UUID.randomUUID(), 1, null, null, null, null));

    assertThat(a).isInstanceOf(AddSnackAugmentation.class);
  }

  @Test
  void parse_addSnackWithNullRecipeId_returnsNull() {
    Augmentation a =
        parser.parse(proposal("ADD_SNACK", UUID.randomUUID(), null, 2, null, null, null, null));

    assertThat(a).isNull();
  }

  @Test
  void parse_addSnackWithNullServings_returnsNull() {
    Augmentation a =
        parser.parse(
            proposal(
                "ADD_SNACK", UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null));

    assertThat(a).isNull();
  }

  @Test
  void parse_ingredientSwapWithBothKeys_returnsTypedSwap() {
    UUID slot = UUID.randomUUID();

    Augmentation a =
        parser.parse(
            proposal("INGREDIENT_SWAP", slot, null, null, "butter", "olive_oil", null, null));

    assertThat(a).isInstanceOf(IngredientSwapAugmentation.class);
    IngredientSwapAugmentation swap = (IngredientSwapAugmentation) a;
    assertThat(swap.fromIngredientKey()).isEqualTo("butter");
    assertThat(swap.toIngredientKey()).isEqualTo("olive_oil");
    assertThat(swap.targetSlotId()).isEqualTo(slot);
  }

  @Test
  void parse_ingredientSwapMissingToKey_returnsNull() {
    Augmentation a =
        parser.parse(
            proposal("INGREDIENT_SWAP", UUID.randomUUID(), null, null, "butter", null, null, null));

    assertThat(a).isNull();
  }

  @Test
  void parse_repairWithIssueAndResolution_returnsTypedRepair() {
    Augmentation a =
        parser.parse(
            proposal("REPAIR", null, null, null, null, null, "protein low", "add a snack"));

    assertThat(a).isInstanceOf(RepairAugmentation.class);
    RepairAugmentation r = (RepairAugmentation) a;
    assertThat(r.issue()).isEqualTo("protein low");
    assertThat(r.resolution()).isEqualTo("add a snack");
    assertThat(r.targetSlotId()).isNull();
  }

  @Test
  void parse_repairWithNullIssue_returnsNull() {
    Augmentation a =
        parser.parse(proposal("REPAIR", UUID.randomUUID(), null, null, null, null, null, "fix"));

    assertThat(a).isNull();
  }

  @Test
  void parse_unknownType_returnsNull() {
    Augmentation a =
        parser.parse(
            proposal("TELEPORT_DINNER", UUID.randomUUID(), null, null, null, null, null, null));

    assertThat(a).isNull();
  }

  @Test
  void parse_nullType_returnsNull() {
    Augmentation a =
        parser.parse(proposal(null, UUID.randomUUID(), null, null, null, null, null, null));

    assertThat(a).isNull();
  }

  @Test
  void parse_nullProposal_returnsNull() {
    assertThat(parser.parse(null)).isNull();
  }
}
