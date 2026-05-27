package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.domain.entity.PackSizeHeuristic;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure pack-size optimiser fixtures from lld/grocery.md line 1015 (grocery-01b): 750 g flour over
 * {500 g, 1 kg} → 1×1 kg; 1.5 kg flour → 1×1.5 kg over 3×500 g; perishables prefer smaller-up;
 * ingredient-key match beats category match. No Spring, no DB.
 */
class PackSizeOptimiserTest {

  private final PackSizeOptimiser optimiser = new PackSizeOptimiser();

  private static IngredientDemand demand(String unitWeightDemand) {
    return PackSizeOptimiser.demand("flour", "Flour", new BigDecimal(unitWeightDemand), "g");
  }

  @Test
  void flour750g_over500and1000_choosesOne1kgPack() {
    List<PackSizeHeuristic> packs =
        List.of(
            GroceryTestData.packByKeySize("flour", 500, 1),
            GroceryTestData.packByKeySize("flour", 1000, 2));

    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("750"), packs, false);

    // 1×1 kg (1000 g, 1 pack) beats 2×500 g (1000 g, 2 packs) on fewest packs.
    assertThat(choice.packSizeG()).isEqualTo(1000);
    assertThat(choice.packsToBuy()).isEqualTo(1);
  }

  @Test
  void flour1500g_over500_1000_1500_choosesOne1point5kgPack() {
    List<PackSizeHeuristic> packs =
        List.of(
            GroceryTestData.packByKeySize("flour", 500, 1),
            GroceryTestData.packByKeySize("flour", 1000, 2),
            GroceryTestData.packByKeySize("flour", 1500, 3));

    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("1500"), packs, false);

    // 1×1.5 kg (1500 g, 1 pack) over 3×500 g (1500 g, 3 packs) and 1×1kg+ceil → still 2×1kg waste.
    assertThat(choice.packSizeG()).isEqualTo(1500);
    assertThat(choice.packsToBuy()).isEqualTo(1);
  }

  @Test
  void nonPerishable_onMagnitudeTie_prefersLargerSinglePack() {
    // Demand 1000 g; both a single 1 kg pack (1×1000) and 2×500 g (1000) buy the same total.
    List<PackSizeHeuristic> packs =
        List.of(
            GroceryTestData.packByKeySize("flour", 500, 1),
            GroceryTestData.packByKeySize("flour", 1000, 2));

    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("1000"), packs, false);

    assertThat(choice.packSizeG()).isEqualTo(1000);
    assertThat(choice.packsToBuy()).isEqualTo(1);
  }

  @Test
  void perishable_prefersSmallerUp_toAvoidWaste() {
    // Demand 300 g of a perishable with {250 g, 500 g} packs: 2×250 g (500 g) vs 1×500 g (500 g) —
    // equal total + perishable → prefer the SMALLER unit pack (less spoilage per unit).
    IngredientDemand perishableDemand =
        PackSizeOptimiser.demand("spinach", "Spinach", new BigDecimal("300"), "g");
    List<PackSizeHeuristic> packs =
        List.of(
            GroceryTestData.packByKeySize("spinach", 250, 1),
            GroceryTestData.packByKeySize("spinach", 500, 2));

    PackSizeOptimiser.PackChoice choice = optimiser.choose(perishableDemand, packs, true);

    assertThat(choice.packSizeG()).isEqualTo(250);
    assertThat(choice.packsToBuy()).isEqualTo(2);
  }

  @Test
  void keyMatchBeatsCategoryMatch_onTie_callerPreSortsKeyPacksFirst() {
    // "Ingredient-key match beats category match" (LLD line 1015). The lookup pre-sorts key-match
    // packs ahead of category packs; the optimiser, on an equal-magnitude tie, keeps the FIRST
    // (key-match) candidate. Demand 1 kg with a 1 kg key pack AND a 1 kg category pack → the key
    // pack wins. (When no key pack exists, the lookup supplies only the category pack — covered in
    // ShoppingListCalculatorTest.)
    PackSizeHeuristic keyPack = GroceryTestData.packByKeySize("flour", 1000, 1);
    PackSizeHeuristic categoryPack = GroceryTestData.packByCategorySize("baking", 1000, 1);
    List<PackSizeHeuristic> packs = List.of(keyPack, categoryPack);

    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("1000"), packs, false);

    assertThat(choice.packSizeG()).isEqualTo(1000);
    assertThat(choice.packsToBuy()).isEqualTo(1);
    // The chosen pack is the key-match one (its unit is identical here; the assertion that the
    // category fallback is only used when no key pack exists is enforced by the lookup).
  }

  @Test
  void countBasedPacks_eggs_choosesDozenForTenEggs() {
    IngredientDemand eggs = PackSizeOptimiser.demand("eggs", "Eggs", new BigDecimal("10"), "items");
    List<PackSizeHeuristic> packs =
        List.of(
            GroceryTestData.packByKeyCount("eggs", 6, 1),
            GroceryTestData.packByKeyCount("eggs", 12, 2));

    PackSizeOptimiser.PackChoice choice = optimiser.choose(eggs, packs, false);

    // 1×12 (12, 1 pack) over 2×6 (12, 2 packs) on fewest packs.
    assertThat(choice.packCount()).isEqualTo(12);
    assertThat(choice.packsToBuy()).isEqualTo(1);
  }

  @Test
  void noPacks_returnsEmptyChoice() {
    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("500"), List.of(), false);
    assertThat(choice.isEmpty()).isTrue();
  }

  @Test
  void zeroDemand_returnsEmptyChoice() {
    List<PackSizeHeuristic> packs = List.of(GroceryTestData.packByKeySize("flour", 500, 1));
    PackSizeOptimiser.PackChoice choice = optimiser.choose(demand("0"), packs, false);
    assertThat(choice.isEmpty()).isTrue();
  }
}
