package com.example.mealprep.planner.domain.service.internal.additions;

import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Curated catalogue of in-meal ingredient additions (Phase 2). Each entry pairs a culinarily
 * sensible whole-food portion with the nutrients it is rich in, so the deterministic gap-fill can
 * rank candidates against a day's residual calories + short micros. Per-100g figures are USDA
 * FoodData Central reference values (the fallback when the nutrition module's ingredient- mapping
 * cache has no live row — see {@code AdditionNutritionResolver}); micro keys use the canonical
 * vocabulary from {@code R__nutrition_seed_dri_defaults.sql}.
 *
 * <p>Deliberately small + diverse: calorie-dense top-ups (oil, nuts, seeds, avocado) for the
 * residual kcal, and produce/dairy (greens, citrus, berries, yogurt) for the micros the recipe pool
 * is thin on. The set spans allergens (nuts, dairy, seeds) on purpose — each is allergy- checked
 * per eater before it is attached.
 */
final class AdditionCandidateCatalogue {

  private AdditionCandidateCatalogue() {}

  /** Micro keys, kept terse at call sites. */
  private static final String VIT_A = "vitamin_a_mcg";

  private static final String VIT_C = "vitamin_c_mg";
  private static final String VIT_E = "vitamin_e_mg";
  private static final String VIT_K = "vitamin_k_mcg";
  private static final String VIT_B6 = "vitamin_b6_mg";
  private static final String VIT_B12 = "vitamin_b12_mcg";
  private static final String FOLATE = "folate_mcg";
  private static final String IRON = "iron_mg";
  private static final String CALCIUM = "calcium_mg";
  private static final String MAGNESIUM = "magnesium_mg";
  private static final String POTASSIUM = "potassium_mg";
  private static final String ZINC = "zinc_mg";
  private static final String COPPER = "copper_mg";
  private static final String PHOSPHORUS = "phosphorus_mg";

  static final List<AdditionCandidate> CANDIDATES =
      List.of(
          c(
              "olive oil",
              "1 tbsp olive oil",
              "1",
              "tbsp",
              "13.5",
              true,
              List.of(VIT_E, VIT_K),
              nps(884, 0, 0, 100, 0, Map.of(VIT_E, "14.4", VIT_K, "60.2"))),
          c(
              "avocado",
              "½ avocado",
              "0.5",
              "whole",
              "100",
              true,
              List.of(POTASSIUM, FOLATE, VIT_K),
              nps(
                  160,
                  2.0,
                  8.5,
                  14.7,
                  6.7,
                  Map.of(
                      POTASSIUM, "485", FOLATE, "81", VIT_K, "21", VIT_E, "2.1", MAGNESIUM, "29"))),
          c(
              "almonds",
              "small handful almonds",
              "28",
              "g",
              "28",
              true,
              List.of(VIT_E, MAGNESIUM, CALCIUM),
              nps(
                  579,
                  21.2,
                  21.6,
                  49.9,
                  12.5,
                  Map.of(VIT_E, "25.6", MAGNESIUM, "270", CALCIUM, "269", PHOSPHORUS, "481"))),
          c(
              "walnuts",
              "small handful walnuts",
              "28",
              "g",
              "28",
              true,
              List.of(MAGNESIUM, COPPER),
              nps(
                  654,
                  15.2,
                  13.7,
                  65.2,
                  6.7,
                  Map.of(MAGNESIUM, "158", COPPER, "1.59", PHOSPHORUS, "346", VIT_B6, "0.54"))),
          c(
              "pumpkin seeds",
              "1 oz pumpkin seeds",
              "28",
              "g",
              "28",
              true,
              List.of(MAGNESIUM, ZINC, IRON),
              nps(
                  559,
                  30.2,
                  10.7,
                  49.1,
                  6.0,
                  Map.of(
                      MAGNESIUM,
                      "592",
                      ZINC,
                      "7.81",
                      IRON,
                      "8.82",
                      COPPER,
                      "1.34",
                      PHOSPHORUS,
                      "1233"))),
          c(
              "chia seeds",
              "1 tbsp chia seeds",
              "12",
              "g",
              "12",
              false,
              List.of(CALCIUM, MAGNESIUM, IRON),
              nps(
                  486,
                  16.5,
                  42.1,
                  30.7,
                  34.4,
                  Map.of(
                      CALCIUM,
                      "631",
                      MAGNESIUM,
                      "335",
                      IRON,
                      "7.72",
                      PHOSPHORUS,
                      "860",
                      ZINC,
                      "4.58"))),
          c(
              "greek yogurt",
              "1 cup plain greek yogurt",
              "170",
              "g",
              "170",
              false,
              List.of(CALCIUM, VIT_B12),
              nps(
                  59,
                  10.2,
                  3.6,
                  0.4,
                  0,
                  Map.of(
                      CALCIUM,
                      "110",
                      VIT_B12,
                      "0.75",
                      PHOSPHORUS,
                      "135",
                      POTASSIUM,
                      "141",
                      ZINC,
                      "0.52"))),
          c(
              "spinach",
              "2 cups raw spinach",
              "60",
              "g",
              "60",
              false,
              List.of(FOLATE, VIT_K, VIT_A, IRON, MAGNESIUM),
              nps(
                  23,
                  2.9,
                  3.6,
                  0.4,
                  2.2,
                  Map.of(
                      FOLATE, "194", VIT_K, "483", VIT_A, "469", VIT_C, "28.1", IRON, "2.71",
                      MAGNESIUM, "79", POTASSIUM, "558", CALCIUM, "99"))),
          c(
              "broccoli",
              "1 cup steamed broccoli",
              "90",
              "g",
              "90",
              false,
              List.of(VIT_C, VIT_K, FOLATE),
              nps(
                  35,
                  2.4,
                  7.2,
                  0.4,
                  3.3,
                  Map.of(VIT_C, "65", VIT_K, "102", FOLATE, "108", VIT_A, "31", POTASSIUM, "293"))),
          c(
              "blueberries",
              "1 cup blueberries",
              "148",
              "g",
              "148",
              false,
              List.of(VIT_C, VIT_K),
              nps(57, 0.7, 14.5, 0.3, 2.4, Map.of(VIT_C, "9.7", VIT_K, "19.3"))),
          c(
              "orange",
              "1 medium orange",
              "130",
              "g",
              "130",
              false,
              List.of(VIT_C, FOLATE),
              nps(
                  47,
                  0.9,
                  11.8,
                  0.1,
                  2.4,
                  Map.of(VIT_C, "53.2", FOLATE, "30", CALCIUM, "40", POTASSIUM, "181"))),
          c(
              "banana",
              "1 medium banana",
              "118",
              "g",
              "118",
              false,
              List.of(POTASSIUM, VIT_B6),
              nps(
                  89,
                  1.1,
                  22.8,
                  0.3,
                  2.6,
                  Map.of(POTASSIUM, "358", VIT_B6, "0.37", VIT_C, "8.7"))));

  private static AdditionCandidate c(
      String key,
      String display,
      String qty,
      String unit,
      String grams,
      boolean fillsCalories,
      List<String> affinity,
      NutritionPerServingDto per100g) {
    return new AdditionCandidate(
        key,
        display,
        new BigDecimal(qty),
        unit,
        new BigDecimal(grams),
        affinity,
        fillsCalories,
        per100g);
  }

  /**
   * Per-100g nutrition builder — micros supplied as decimal strings keyed by canonical micro key.
   */
  private static NutritionPerServingDto nps(
      int kcal,
      double protein,
      double carbs,
      double fat,
      double fibre,
      Map<String, String> micros) {
    return new NutritionPerServingDto(
        kcal,
        BigDecimal.valueOf(protein),
        BigDecimal.valueOf(carbs),
        BigDecimal.valueOf(fat),
        BigDecimal.valueOf(fibre),
        micros.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, e -> new BigDecimal(e.getValue()))));
  }
}
