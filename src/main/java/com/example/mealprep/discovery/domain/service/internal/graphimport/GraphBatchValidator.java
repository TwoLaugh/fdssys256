package com.example.mealprep.discovery.domain.service.internal.graphimport;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * G06 fail-closed dish-validation matrix — pure functions, unit-testable without Spring. The engine
 * validates almost nothing on import (empty {@code mealTypes} is silently unplannable, unknown
 * equipment silently unfillable, blank-key lines silently skipped), so this validator is the
 * guarantee-holder for every graph dish.
 */
public final class GraphBatchValidator {

  /** The engine slot-kind set — the ONLY mealTypes vocabulary a graph dish may carry. */
  static final Set<String> ALLOWED_MEAL_TYPES = Set.of("breakfast", "lunch", "dinner", "snack");

  private GraphBatchValidator() {}

  /**
   * All violations for one dish (empty list = importable). Reasons are stable strings — they land
   * verbatim in the ingest report.
   */
  public static List<String> dishViolations(
      ImportedRecipeData data, Set<String> equipmentCatalogue) {
    List<String> violations = new ArrayList<>();
    violations.addAll(
        mealTypesViolations(data.metadata() == null ? null : data.metadata().mealTypes()));
    violations.addAll(
        equipmentViolations(
            data.metadata() == null ? null : data.metadata().equipmentRequired(),
            equipmentCatalogue));
    violations.addAll(ingredientViolations(data.ingredients()));
    servingsViolation(data.metadata() == null ? null : data.metadata().servings())
        .ifPresent(violations::add);
    return violations;
  }

  /**
   * Hard requirement #1: non-empty, all lowercase, all within the engine slot-kind set. An empty
   * list would import fine and then be invisible to every per-kind pool read.
   */
  public static List<String> mealTypesViolations(List<String> mealTypes) {
    if (mealTypes == null || mealTypes.isEmpty()) {
      return List.of("mealTypes empty");
    }
    List<String> violations = new ArrayList<>();
    for (String mealType : mealTypes) {
      if (mealType == null || !mealType.equals(mealType.toLowerCase(Locale.ROOT))) {
        violations.add("mealType not lowercase: " + mealType);
      } else if (!ALLOWED_MEAL_TYPES.contains(mealType)) {
        violations.add("unknown mealType: " + mealType);
      }
    }
    return violations;
  }

  /**
   * Engine-side backstop of the spike's frozen equipment map: every required name must be in the
   * seeded {@code provision_equipment_catalogue} (case-sensitive — the catalogue is lowercase
   * snake_case and {@code HardFilterRunner} lowercases the household side, so a wrongly-cased name
   * here is exactly the silent-unfillable trap this check exists for).
   */
  public static List<String> equipmentViolations(
      List<String> equipmentRequired, Set<String> equipmentCatalogue) {
    if (equipmentRequired == null || equipmentRequired.isEmpty()) {
      return List.of();
    }
    List<String> violations = new ArrayList<>();
    for (String name : equipmentRequired) {
      if (name == null || !equipmentCatalogue.contains(name)) {
        violations.add("unknown equipment: " + name);
      }
    }
    return violations;
  }

  /** Consumed-basis grams contract: {@code unit == "g"}, positive quantity, normal-form key. */
  public static List<String> ingredientViolations(
      List<ImportedRecipeData.ImportedIngredient> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return List.of("ingredients empty");
    }
    List<String> violations = new ArrayList<>();
    for (ImportedRecipeData.ImportedIngredient ingredient : ingredients) {
      String at = "ingredient line " + ingredient.lineOrder();
      if (!"g".equals(ingredient.unit())) {
        violations.add(at + ": unit must be \"g\", got " + ingredient.unit());
      }
      BigDecimal quantity = ingredient.quantity();
      if (quantity == null || quantity.signum() <= 0) {
        violations.add(at + ": quantity must be > 0, got " + quantity);
      }
      String key = ingredient.ingredientMappingKey();
      if (key == null || !key.equals(IngredientMappingKeys.normalise(key))) {
        violations.add(at + ": ingredientMappingKey not normal-form: " + key);
      }
    }
    return violations;
  }

  /**
   * Decision D2: batch payloads are per-serving ({@code servings == 1}); anything else means a
   * silently pre-scaled batch and is rejected. Revisit only if D2 flips.
   */
  public static java.util.Optional<String> servingsViolation(Integer servings) {
    if (servings == null || servings != 1) {
      return java.util.Optional.of("servings must be 1 (D2), got " + servings);
    }
    return java.util.Optional.empty();
  }
}
