package com.example.mealprep.planner.domain.service.internal.additions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link AdditionNutritionResolver}'s portion-scaling of catalogue (USDA fallback)
 * nutrition. Constructed with a {@code null} query service so the live-cache branch is skipped and
 * the deterministic baked-value scaling is exercised directly (the live-lookup branch is plain
 * delegation). All catalogue micro values are exact decimals → exact scaled assertions.
 */
class AdditionNutritionResolverTest {

  private final AdditionNutritionResolver resolver = new AdditionNutritionResolver(null);

  private static AdditionCandidate candidate(String key) {
    return AdditionCandidateCatalogue.CANDIDATES.stream()
        .filter(c -> c.ingredientKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void scales_olive_oil_per_100g_by_portion_grams() {
    // 1 tbsp = 13.5 g → factor 0.135. 884 kcal/100g → 119; 100 g fat/100g → 13.50 g.
    Addition a = resolver.resolve(candidate("olive oil"));

    assertThat(a.kind()).isEqualTo(AdditionKind.INGREDIENT);
    assertThat(a.ingredientMappingKey()).isEqualTo("olive oil");
    NutritionPerServingDto n = a.nutrition();
    assertThat(n.calories()).isEqualTo(119);
    assertThat(n.fatG()).isEqualByComparingTo("13.50");
    // vitamin_e 14.4 mg/100g × 0.135 = 1.944 mg, tagged derived provenance.
    assertThat(n.micros().get("vitamin_e_mg")).isEqualByComparingTo("1.944");
    assertThat(n.microSources().get("vitamin_e_mg")).isEqualTo("derived");
  }

  @Test
  void scales_a_produce_candidate_and_tags_all_micros_derived() {
    // ½ avocado = 100 g → factor 1.0 → per-100g values pass through unchanged.
    Addition a = resolver.resolve(candidate("avocado"));
    NutritionPerServingDto n = a.nutrition();

    assertThat(n.calories()).isEqualTo(160);
    assertThat(n.micros().get("potassium_mg")).isEqualByComparingTo("485");
    assertThat(n.micros().keySet())
        .allSatisfy(k -> assertThat(n.microSources().get(k)).isEqualTo("derived"));
  }

  @Test
  void catalogue_is_non_empty_and_every_candidate_resolves() {
    assertThat(AdditionCandidateCatalogue.CANDIDATES).isNotEmpty();
    for (AdditionCandidate c : AdditionCandidateCatalogue.CANDIDATES) {
      Addition a = resolver.resolve(c);
      assertThat(a.nutrition()).isNotNull();
      assertThat(a.nutrition().calories()).isGreaterThanOrEqualTo(0);
      assertThat(a.ingredientMappingKey()).isEqualTo(c.ingredientKey());
    }
  }
}
