package com.example.mealprep.preference.domain.service.internal;

import java.util.Map;
import java.util.Set;

/**
 * Static lookup mapping a {@code medicalDiets} entry (e.g. {@code low_sodium}) to the set of
 * ingredient keys it implicitly rejects (e.g. {@code salt}, {@code soy_sauce}). The diet name
 * itself is also matched directly against ingredient keys; this lookup expands the rule.
 *
 * <p>Reference data — small and stable. v1 list; expand as the product surfaces more medical diets.
 */
final class MedicalDietRules {

  private MedicalDietRules() {}

  private static final Map<String, Set<String>> DIET_TO_REJECTED_KEYS =
      Map.of(
          "low_sodium", Set.of("salt", "soy_sauce", "fish_sauce", "msg", "bouillon"),
          "low_sugar",
              Set.of("sugar", "honey", "syrup", "agave", "high_fructose_corn_syrup", "molasses"),
          "low_fodmap", Set.of("garlic", "onion", "wheat", "lactose", "honey"),
          "diabetic", Set.of("sugar", "honey", "syrup", "high_fructose_corn_syrup"),
          "low_potassium", Set.of("banana", "potato", "spinach", "tomato"),
          "low_phosphorus", Set.of("dairy", "cheese", "milk", "nuts"));

  /** Returns the implicit ingredient-key rejections for the supplied diet; empty for unknowns. */
  static Set<String> rejectedKeysFor(String medicalDiet) {
    if (medicalDiet == null) {
      return Set.of();
    }
    return DIET_TO_REJECTED_KEYS.getOrDefault(medicalDiet, Set.of());
  }
}
