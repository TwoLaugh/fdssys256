package com.example.mealprep.e2e.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reusable request-body builders for the E2E step-definition library. These mirror the concrete,
 * validator-passing shapes the production DTOs require (see the per-module {@code *TestData}
 * builders in {@code src/test}); building them as plain {@link Map}s here keeps the e2e source set
 * free of any compile dependency on the production DTO classes (the harness is a black-box HTTP
 * client — decision D2).
 *
 * <p>Every builder returns a fresh mutable structure so a step can tweak one field (to drive a
 * validation-error pathway) without mutating a shared default.
 *
 * <p><b>Ingredient-mapping-key convention.</b> Keys are written in the canonical space-form ({@code
 * "chicken breast"}, not {@code "chicken.breast"}) — the lowercase, whitespace-collapsed form
 * {@code IngredientMappingKeys.normalise} produces from real ingredient text, and the form the
 * grocery reference-price seed + recipe-import fixture use. This is what lets a plannable recipe's
 * {@code chicken breast} line resolve against the reference snapshot so the GROC-03 cost projection
 * carries a real estimate. Do NOT reintroduce dot-form: the normaliser does not bridge {@code .} ↔
 * {@code space}, so a dot-key silently fails to match the seed.
 */
public final class TestPayloads {

  private TestPayloads() {}

  // ---------------- Recipe ----------------

  /**
   * A valid {@code POST /api/v1/recipes} body: unique ingredient line-orders, contiguous 1..N
   * method steps, and {@code totalTimeMins == prepTimeMins + cookTimeMins} (the
   * {@code @ValidRecipeMetadata} rule).
   */
  public static Map<String, Object> manualRecipe(String name) {
    return new java.util.HashMap<>(
        Map.of(
            "name", name,
            "description", "An E2E-authored recipe.",
            "ingredients", defaultIngredients(),
            "method", defaultMethod(),
            "metadata", defaultMetadata(),
            "tags", defaultTags()));
  }

  /**
   * A valid {@code POST /api/v1/recipes} body tuned to survive the planner's Stage-A hard filters
   * so the seeded recipe becomes a plannable candidate for a slot of {@code mealType}
   * (breakfast/lunch/dinner/snack — case-insensitive; the {@code HardFilterRunner} lower-cases both
   * sides). The two filter pitfalls a plain {@link #manualRecipe} body trips are corrected here:
   *
   * <ul>
   *   <li><b>Kind:</b> {@code metadata.mealTypes} is set to the single {@code mealType} so {@code
   *       HardFilterRunner.matchesKind} matches the slot.
   *   <li><b>Time budget:</b> {@code totalTimeMins == 25} — well under the default-household slot's
   *       30-min budget × the 1.5 overshoot ratio (cap 45), so {@code withinTimeBudget} passes.
   *   <li><b>Equipment:</b> {@code equipmentRequired} is EMPTY. A fresh household has no
   *       provisions, so any non-empty equipment list would be filtered out (no available equipment
   *       to match).
   * </ul>
   *
   * <p>A single ingredient with a valid {@code ingredientMappingKey} passes the hard-constraint
   * filter for a fresh user (no hard constraints). The {@code name} must be unique per recipe so
   * the content fingerprint stays distinct (each seed is a separate catalogue row, not a dedup
   * collapse).
   */
  public static Map<String, Object> plannableRecipe(String name, String mealType) {
    Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("servings", 2);
    metadata.put("prepTimeMins", 10);
    metadata.put("cookTimeMins", 15);
    metadata.put("totalTimeMins", 25);
    metadata.put("equipmentRequired", List.of());
    metadata.put("fridgeDays", 3);
    metadata.put("freezerWeeks", 2);
    metadata.put("packable", true);
    metadata.put("cuisine", "Generic");
    metadata.put("mealTypes", List.of(mealType));

    List<Map<String, Object>> ingredients = new ArrayList<>();
    ingredients.add(ingredient(0, "chicken breast", "Chicken breast", "300.000", "g"));

    List<Map<String, Object>> method = new ArrayList<>();
    method.add(methodStep(1, "Prepare and cook the " + name + ".", 25));

    return new java.util.HashMap<>(
        Map.of(
            "name", name,
            "description", "An E2E-authored plannable recipe.",
            "ingredients", ingredients,
            "method", method,
            "metadata", metadata,
            "tags", defaultTags()));
  }

  /**
   * A valid {@code PUT /api/v1/recipes/{id}} (manual edit) body — same shape as a create body plus
   * a {@code changeReason} and the caller-supplied {@code expectedOptimisticVersion}. The second
   * method step's duration is bumped so the computed diff is non-empty (a no-op edit is rejected
   * 400 {@code no-changes}).
   */
  public static Map<String, Object> manualEdit(String name, long expectedOptimisticVersion) {
    List<Map<String, Object>> editedMethod = defaultMethod();
    editedMethod.get(1).put("instruction", "Add passata and simmer for 35 minutes.");
    editedMethod.get(1).put("durationMinutes", 35);
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("name", name);
    body.put("description", "An E2E-authored recipe.");
    body.put("ingredients", defaultIngredients());
    body.put("method", editedMethod);
    body.put("metadata", defaultMetadata());
    body.put("tags", defaultTags());
    body.put("changeReason", "Simmer longer for deeper flavour.");
    body.put("expectedOptimisticVersion", expectedOptimisticVersion);
    return body;
  }

  private static List<Map<String, Object>> defaultIngredients() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(ingredient(0, "spaghetti", "Spaghetti", "400.000", "g"));
    out.add(ingredient(1, "beef mince", "Lean beef mince", "500.000", "g"));
    out.add(ingredient(2, "passata", "Passata", "700.000", "g"));
    return out;
  }

  private static Map<String, Object> ingredient(
      int lineOrder, String key, String displayName, String quantity, String unit) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("lineOrder", lineOrder);
    m.put("ingredientMappingKey", key);
    m.put("displayName", displayName);
    m.put("quantity", quantity);
    m.put("unit", unit);
    m.put("optional", false);
    return m;
  }

  private static List<Map<String, Object>> defaultMethod() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(methodStep(1, "Brown the mince in a wide pan.", 8));
    out.add(methodStep(2, "Add passata and simmer for 25 minutes.", 25));
    out.add(methodStep(3, "Cook spaghetti to al dente; drain.", 9));
    return out;
  }

  private static Map<String, Object> methodStep(int stepNumber, String instruction, int duration) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("stepNumber", stepNumber);
    m.put("instruction", instruction);
    m.put("durationMinutes", duration);
    return m;
  }

  private static Map<String, Object> defaultMetadata() {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("servings", 4);
    m.put("prepTimeMins", 15);
    m.put("cookTimeMins", 30);
    m.put("totalTimeMins", 45);
    m.put("equipmentRequired", List.of("large pan", "colander"));
    m.put("fridgeDays", 3);
    m.put("freezerWeeks", 2);
    m.put("packable", true);
    m.put("cuisine", "Italian");
    m.put("mealTypes", List.of("DINNER"));
    return m;
  }

  private static Map<String, Object> defaultTags() {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("protein", "beef");
    m.put("cookingMethod", "stovetop");
    m.put("complexity", "MODERATE");
    m.put("flavourProfile", List.of("savoury", "umami"));
    m.put("dietaryFlags", List.of());
    return m;
  }

  // ---------------- Nutrition ----------------

  /**
   * A valid {@code PUT /api/v1/nutrition/targets} (full replacement) body mirroring the production
   * {@code NutritionTestData.defaultUpdateRequest}. {@code expectedVersion} is the JPA optimistic
   * version the next write must match (0 for the first write).
   */
  public static Map<String, Object> nutritionTargets(long expectedVersion) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("goal", "MAINTAIN");
    body.put("calories", calorieTarget());
    body.put("protein", macro("120.0", "daily_floor", "LOWER_FLOOR"));
    body.put("carbs", macro("250.0", "weekly_average", "BOTH_BOUNDED"));
    body.put("fat", macro("70.0", "weekly_average", "BOTH_BOUNDED"));
    body.put("fibre", macro("30.0", "daily_floor", "LOWER_FLOOR"));
    body.put("satFat", macroNoEnforcement("20.0", "UPPER_LIMIT"));
    body.put("notes", "E2E default notes");
    body.put("perMealDistribution", perMealDistribution());
    body.put("microTargets", microTargets());
    body.put("eatingWindow", eatingWindowDisabled());
    body.put("activityAdjustments", activityAdjustments());
    body.put("expectedVersion", expectedVersion);
    return body;
  }

  private static Map<String, Object> calorieTarget() {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("dailyTarget", 2000);
    m.put("toleranceUnder", 100);
    m.put("toleranceOver", 150);
    m.put("enforcement", "weekly_average");
    m.put("direction", "BOTH_BOUNDED");
    return m;
  }

  private static Map<String, Object> macro(String targetG, String enforcement, String direction) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("targetG", targetG);
    m.put("floorG", null);
    m.put("enforcement", enforcement);
    m.put("direction", direction);
    return m;
  }

  private static Map<String, Object> macroNoEnforcement(String targetG, String direction) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("targetG", targetG);
    m.put("floorG", null);
    m.put("enforcement", null);
    m.put("direction", direction);
    return m;
  }

  private static List<Map<String, Object>> perMealDistribution() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(perMeal("BREAKFAST", 500, "30.0"));
    out.add(perMeal("LUNCH", 600, "40.0"));
    out.add(perMeal("DINNER", 700, "40.0"));
    out.add(perMeal("SNACKS", 200, "10.0"));
    return out;
  }

  private static Map<String, Object> perMeal(String slot, int calories, String proteinG) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("mealSlot", slot);
    m.put("calorieTarget", calories);
    m.put("proteinTargetG", proteinG);
    return m;
  }

  private static List<Map<String, Object>> microTargets() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(micro("iron_mg", "18.0"));
    out.add(micro("vitamin_d_iu", "800.0"));
    return out;
  }

  private static Map<String, Object> micro(String key, String targetValue) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("nutrientKey", key);
    m.put("targetValue", targetValue);
    m.put("upperLimit", null);
    m.put("sourcePreference", null);
    m.put("notes", null);
    return m;
  }

  private static Map<String, Object> eatingWindowDisabled() {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("enabled", false);
    m.put("windowStart", null);
    m.put("windowEnd", null);
    m.put("notes", null);
    return m;
  }

  private static List<Map<String, Object>> activityAdjustments() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(activity("REST_DAY", -200, -30));
    out.add(activity("TRAINING_DAY", 300, 50));
    return out;
  }

  private static Map<String, Object> activity(String level, int calorieModifier, int carbModifier) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("activityLevel", level);
    m.put("calorieModifier", calorieModifier);
    m.put("carbModifierG", carbModifier);
    return m;
  }

  /** A valid {@code POST /api/v1/nutrition/intake/{date}/snacks} body (MANUAL source). */
  public static Map<String, Object> snack(String freeText) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("freeText", freeText);
    m.put("ingredientMappingKey", null);
    m.put("quantityG", "30.0");
    m.put("calories", 180);
    m.put("proteinG", "7.0");
    m.put("carbsG", "6.0");
    m.put("fatG", "15.0");
    m.put("fibreG", "3.0");
    m.put("micros", null);
    m.put("source", "MANUAL");
    m.put("deductFromPantry", null);
    return m;
  }
}
