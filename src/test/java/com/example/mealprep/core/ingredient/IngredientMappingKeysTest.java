package com.example.mealprep.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link IngredientMappingKeys}. Pure static logic — no Spring context. */
class IngredientMappingKeysTest {

  @Test
  void null_returns_null() {
    assertThat(IngredientMappingKeys.normalise(null)).isNull();
  }

  @Test
  void empty_and_whitespace_only_returns_empty() {
    assertThat(IngredientMappingKeys.normalise("")).isEqualTo("");
    assertThat(IngredientMappingKeys.normalise("   ")).isEqualTo("");
    assertThat(IngredientMappingKeys.normalise("   \t\n  ")).isEqualTo("");
  }

  @Test
  void lowercases_trims_and_collapses_internal_whitespace() {
    assertThat(IngredientMappingKeys.normalise("Chicken Breast")).isEqualTo("chicken breast");
    assertThat(IngredientMappingKeys.normalise("chicken breast")).isEqualTo("chicken breast");
    assertThat(IngredientMappingKeys.normalise("  chicken   breast ")).isEqualTo("chicken breast");
    assertThat(IngredientMappingKeys.normalise("a\t\tb\n\nc")).isEqualTo("a b c");
  }

  @Test
  void case_and_whitespace_variants_collapse_to_the_same_key() {
    String canonical = IngredientMappingKeys.normalise("chicken breast");
    assertThat(IngredientMappingKeys.normalise("Chicken Breast")).isEqualTo(canonical);
    assertThat(IngredientMappingKeys.normalise("  chicken   breast ")).isEqualTo(canonical);
    assertThat(canonical).isEqualTo("chicken breast");
  }

  @Test
  void is_idempotent() {
    for (String raw : new String[] {"  Chicken   Breast  ", "chicken breast", "", "   ", "A B"}) {
      String once = IngredientMappingKeys.normalise(raw);
      assertThat(IngredientMappingKeys.normalise(once)).isEqualTo(once);
    }
  }

  @Test
  void uses_root_locale_for_deterministic_lowercasing() {
    // Locale.ROOT lowercases ASCII 'I' to 'i' regardless of the JVM's default locale
    // (a Turkish-locale default would otherwise produce a dotless 'ı').
    assertThat(IngredientMappingKeys.normalise("CHICKEN STRIPS")).isEqualTo("chicken strips");
  }
}
