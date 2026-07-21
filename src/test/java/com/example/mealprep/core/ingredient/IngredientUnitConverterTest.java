package com.example.mealprep.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IngredientUnitConverter}. */
class IngredientUnitConverterTest {

  private static double grams(String q, String unit, String key) {
    Optional<BigDecimal> g = IngredientUnitConverter.toGrams(new BigDecimal(q), unit, key);
    assertThat(g).as("%s %s of %s convertible", q, unit, key).isPresent();
    return g.get().doubleValue();
  }

  @Test
  void weight_units_are_exact() {
    assertThat(grams("1", "lb", "beef"))
        .isCloseTo(453.59, org.assertj.core.data.Offset.offset(0.1));
    assertThat(grams("8", "oz", "cheddar cheese"))
        .isCloseTo(226.8, org.assertj.core.data.Offset.offset(0.5));
    assertThat(grams("500", "g", "flour")).isEqualTo(500.0);
    assertThat(grams("1", "kg", "sugar")).isEqualTo(1000.0);
  }

  @Test
  void volume_uses_per_ingredient_density() {
    // 2 tbsp olive oil = 2 * 14.79 * 0.92 ≈ 27.2 g
    assertThat(grams("2", "tbsp", "olive oil"))
        .isCloseTo(27.2, org.assertj.core.data.Offset.offset(0.3));
    // 1 cup flour = 236.6 * 0.53 ≈ 125 g
    assertThat(grams("1", "cup", "all-purpose flour"))
        .isCloseTo(125.4, org.assertj.core.data.Offset.offset(1.0));
    // 1 cup water = 236.6 g (default density 1.0)
    assertThat(grams("1", "cup", "water"))
        .isCloseTo(236.6, org.assertj.core.data.Offset.offset(0.5));
  }

  @Test
  void count_units_are_not_converted_so_they_match_count_packs() {
    // count-bought ingredients stay raw (a count pack matches them, not a gram pack)
    assertThat(IngredientUnitConverter.toGrams(new BigDecimal("2"), "clove", "garlic")).isEmpty();
    assertThat(IngredientUnitConverter.toGrams(new BigDecimal("3"), "", "egg")).isEmpty();
    assertThat(IngredientUnitConverter.toGrams(new BigDecimal("1"), "", "onion")).isEmpty();
  }

  @Test
  void unrecognised_unit_is_not_convertible() {
    assertThat(IngredientUnitConverter.toGrams(new BigDecimal("1"), "blorp", "salt")).isEmpty();
  }

  @Test
  void unknown_volume_ingredient_falls_back_to_water_density() {
    // a volume of an unknown ingredient still converts (default density), just less precisely
    assertThat(grams("1", "tbsp", "mystery sauce"))
        .isCloseTo(14.79, org.assertj.core.data.Offset.offset(0.1));
  }

  @Test
  void null_or_zero_is_empty() {
    assertThat(IngredientUnitConverter.toGrams(null, "g", "salt")).isEmpty();
    assertThat(IngredientUnitConverter.toGrams(BigDecimal.ZERO, "g", "salt")).isEmpty();
  }
}
