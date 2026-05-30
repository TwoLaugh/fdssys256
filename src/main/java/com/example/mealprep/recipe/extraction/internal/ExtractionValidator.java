package com.example.mealprep.recipe.extraction.internal;

import com.example.mealprep.recipe.extraction.ParsedRecipe;
import org.springframework.stereotype.Component;

/**
 * Layer 5 — validator, per {@code recipe-extraction-pipeline.md} §"Layer 5 — Validator". Pure code,
 * no AI. v1 enforces the hard-fail contract the pre-refactor extractors used: a recipe is
 * sufficient only when it has a non-blank name, at least one ingredient, and at least one method
 * step.
 *
 * <p>This is exactly the {@code isComplete} predicate the old {@code HtmlImportParser} applied per
 * strategy and the {@code name + recipeIngredient + recipeInstructions} contract the discovery
 * extractor implied — preserved verbatim so import outcomes do not change. Soft-fail warnings (time
 * inconsistency, unparseable lines) are a deferred enhancement (LLD §Layer 5) and are not emitted
 * in v1.
 */
@Component
public class ExtractionValidator {

  /** True when the recipe satisfies the hard-fail minimum (name + ≥1 ingredient + ≥1 step). */
  public boolean isSufficient(ParsedRecipe recipe) {
    return recipe != null
        && recipe.name() != null
        && !recipe.name().isBlank()
        && recipe.ingredients() != null
        && !recipe.ingredients().isEmpty()
        && recipe.methodSteps() != null
        && !recipe.methodSteps().isEmpty();
  }
}
