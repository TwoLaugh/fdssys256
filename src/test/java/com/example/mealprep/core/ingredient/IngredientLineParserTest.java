package com.example.mealprep.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IngredientLineParser} over real datahive ingredient lines. */
class IngredientLineParserTest {

  private static IngredientLineParser.Parsed parse(String line) {
    return IngredientLineParser.parse(line);
  }

  private static void assertParse(String line, String name, String qty, String unit) {
    IngredientLineParser.Parsed p = parse(line);
    assertThat(p.name()).as("name of \"%s\"", line).isEqualTo(name);
    if (qty == null) {
      assertThat(p.quantity()).as("qty of \"%s\"", line).isNull();
    } else {
      assertThat(p.quantity()).as("qty of \"%s\"", line).isNotNull();
      assertThat(p.quantity().compareTo(new BigDecimal(qty)))
          .as("qty of \"%s\" == %s", line, qty)
          .isZero();
    }
    assertThat(p.unit()).as("unit of \"%s\"", line).isEqualTo(unit);
  }

  @Test
  void parses_quantity_unit_and_name() {
    assertParse("2 cups boiling water", "water", "2", "cup");
    assertParse("4 tbsp olive oil, divided", "olive oil", "4", "tbsp");
    assertParse("1 tablespoon unsalted butter", "unsalted butter", "1", "tbsp");
    assertParse("1 Tablespoon Butter", "butter", "1", "tbsp");
    assertParse("2 teaspoons cornstarch", "cornstarch", "2", "tsp");
    assertParse("4 ounces chevre", "chevre", "4", "oz");
    assertParse("1 cup quinoa", "quinoa", "1", "cup");
  }

  @Test
  void parses_fractions_and_mixed_numbers() {
    assertParse("1/4 cup chopped onion", "onion", "0.25", "cup");
    assertParse("1/2 cup all-purpose flour", "all-purpose flour", "0.5", "cup");
    assertParse("3/4 cup chopped fresh basil", "fresh basil", "0.75", "cup");
    assertParse("1 1/2 lbs asparagus, trimmed", "asparagus", "1.5", "lb");
    assertParse("1/3 cup brown sugar, packed", "brown sugar", "0.3333", "cup"); // 1/3 → 0.3333 (scale 4)
  }

  @Test
  void handles_count_units_and_abbreviations() {
    assertParse("2 cloves garlic", "garlic", "2", "clove");
    assertParse("1 c. all-purpose flour", "all-purpose flour", "1", "cup");
    assertParse("1/2 tsp cayenne pepper", "cayenne pepper", "0.5", "tsp");
  }

  @Test
  void strips_preparation_into_its_own_field() {
    IngredientLineParser.Parsed p = parse("2 cloves Garlic, Minced");
    assertThat(p.name()).isEqualTo("garlic");
    assertThat(p.unit()).isEqualTo("clove");
    assertThat(p.preparation()).contains("minced");

    IngredientLineParser.Parsed boiling = parse("2 cups boiling water");
    assertThat(boiling.preparation()).contains("boiling");
  }

  @Test
  void bare_ingredient_with_no_quantity() {
    assertParse("Cinnamon", "cinnamon", null, null);
    assertParse("softened butter", "butter", null, null);
    assertParse("salt and pepper, to taste", "salt and pepper", null, null);
  }

  @Test
  void strips_leading_bullets_and_trailing_prices() {
    assertParse("* 15 oz can pumpkin puree $2.00", "can pumpkin puree", "15", "oz");
    IngredientLineParser.Parsed p = parse("* 2 clove of garlic*, crushed");
    assertThat(p.name()).isEqualTo("garlic");
    assertThat(p.quantity().compareTo(new BigDecimal("2"))).isZero();
    assertThat(p.unit()).isEqualTo("clove");
  }

  @Test
  void quantity_unit_variants_collapse_to_the_same_name() {
    // The core de-dup win: different qty/unit spellings of one ingredient → identical name.
    String n1 = parse("1 tablespoon olive oil").name();
    String n2 = parse("4 tbsp olive oil, divided").name();
    String n3 = parse("1/4 cup olive oil").name();
    assertThat(n1).isEqualTo("olive oil");
    assertThat(n2).isEqualTo("olive oil");
    assertThat(n3).isEqualTo("olive oil");
  }

  @Test
  void empty_or_null_is_safe() {
    assertThat(parse(null).name()).isEmpty();
    assertThat(parse("   ").name()).isEmpty();
    assertThat(parse(null).confidence()).isEqualTo(BigDecimal.ZERO);
  }
}
