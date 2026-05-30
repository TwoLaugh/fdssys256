package com.example.mealprep.recipe.extraction;

import java.util.List;

/**
 * Canonical structured recipe produced by the shared {@link RecipeExtractionService}. This is the
 * single internal shape the layer stack emits; the two consumers (recipe URL-import and discovery
 * ingest) translate it to their own carrier types via thin adapters, preserving each consumer's
 * existing downstream mapping (notably its {@code ingredient_mapping_key} derivation).
 *
 * <p>Deliberately a superset of what either consumer needs so neither loses a field across the
 * refactor:
 *
 * <ul>
 *   <li>{@code rawLine} on each ingredient is the verbatim source line (e.g. {@code "2 tbsp olive
 *       oil"}). It is the unit the recipe URL-import path maps from today (string lines).
 *   <li>{@code prep/cook/total/servings/cuisine} mirror the schema.org fields both consumers read.
 * </ul>
 *
 * <p>Nutrition is intentionally absent — both consumers discard external nutrition and recompute it
 * (LLD §Layer 1; {@code design/recipe-system.md}).
 */
public record ParsedRecipe(
    String sourceUrl,
    String name,
    String description,
    List<ParsedIngredient> ingredients,
    List<ParsedMethodStep> methodSteps,
    Integer prepTimeMinutes,
    Integer cookTimeMinutes,
    Integer totalTimeMinutes,
    Integer servings,
    String cuisine,
    ExtractionProvenance provenance) {

  public ParsedRecipe {
    ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    methodSteps = methodSteps == null ? List.of() : List.copyOf(methodSteps);
  }

  /**
   * One ingredient line. {@code rawLine} is the verbatim source text; the structured {@code
   * quantity/unit/preparation} fields are populated when an extractor can parse them and {@code
   * null} otherwise (v1 JSON-LD/microdata ship {@code rawLine} only — quantity parsing is a
   * deferred enhancement, matching the as-built extractors).
   */
  public record ParsedIngredient(
      String rawLine,
      java.math.BigDecimal quantity,
      String unit,
      String preparation,
      boolean optional) {

    /** Convenience constructor for the v1 "raw line only" case. */
    public static ParsedIngredient ofLine(String rawLine) {
      return new ParsedIngredient(rawLine, null, null, null, false);
    }
  }

  public record ParsedMethodStep(int stepNumber, String instruction) {}
}
