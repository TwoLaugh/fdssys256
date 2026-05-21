package com.example.mealprep.preference.domain.document;

import com.example.mealprep.preference.validation.ValidNoveltyTolerance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;

/**
 * JSONB document persisted alongside {@code LifestyleConfig}. Mirrors the OpenAPI {@code
 * LifestyleConfigDocument} schema and the HLD Tier-3 shape in {@code design/preference-model.md}
 * lines 190-291.
 *
 * <p>Persisted via hypersistence-utils {@code JsonBinaryType}; top-level fields drive the
 * section-level diff in {@code LifestyleConfigServiceImpl#update}. Every nested type is a {@code
 * public static record} so Jackson can round-trip the whole tree by reflection. Nullable fields are
 * permitted everywhere: lifestyle config is partially populated during onboarding and only fully
 * populated once the wizard ships.
 *
 * <p>Field-level Jakarta annotations are deliberately light: per the ticket scope, the only
 * cross-record validator that ships in 01d is {@link ValidNoveltyTolerance}. Per-section coherence
 * validators (e.g. meal-timing windows monotonic, batch-cooking prep-day Sun-Sat) ship as needed
 * when failure modes surface.
 */
public record LifestyleConfigDocument(
    @Valid MealStructure mealStructure,
    @Valid MealTiming mealTiming,
    @Valid @ValidNoveltyTolerance NoveltyTolerance noveltyTolerance,
    @Valid CookingContexts cookingContexts,
    @Valid BatchCooking batchCooking,
    @Valid ReheatingPreferences reheatingPreferences,
    @Valid EatingContext eatingContext,
    @Valid SeasonalPreferences seasonalPreferences,
    @Valid MealTypePreferences mealTypePreferences,
    @Valid Accompaniments accompaniments,
    @Valid GroceryQualityPreferences groceryQualityPreferences,
    @Valid PantryTracking pantryTracking) {

  /** Empty document — all sections null. Used as the seed shape from {@code initialise()}. */
  public static LifestyleConfigDocument empty() {
    return new LifestyleConfigDocument(
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  // ---- Meal structure ----

  public record MealStructure(
      DayProfile weekday, DayProfile weekend, List<RecurringSkip> recurringSkips) {}

  public record DayProfile(List<String> meals, SnackPolicy snacks) {}

  public record SnackPolicy(boolean planned, String style, String notes) {}

  public record RecurringSkip(String day, String meal, String reason) {}

  // ---- Meal timing ----

  public record MealTiming(MealSchedule preferredSchedule, String flexibility, String notes) {}

  /**
   * Free-form map of meal slot → time-range string (e.g. {@code "07:00-08:00"}). Keys are
   * canonical-cased ({@code breakfast}, {@code lunch}, {@code dinner}) by convention but not
   * enforced here — weekend-brunch values are allowed.
   */
  public record MealSchedule(Map<String, String> times) {}

  // ---- Novelty tolerance ----

  public record NoveltyTolerance(
      Map<String, NoveltyMode> bySlot,
      Map<String, Integer> recipeRepeatCooldownWeeks,
      Map<String, String> ingredientFrequencyCaps) {}

  /**
   * Per-slot novelty mode. Mode-specific fields are nullable: {@code rotation} reads {@link
   * #rotationSize}, {@code batch_repeat} reads {@link #maxConsecutiveSame}, {@code high_variety}
   * reads {@link #newPerWeek}; {@code static} reads none. Validation is performed by {@link
   * ValidNoveltyTolerance} at the parent level — the validator inspects mode-keyed entries.
   */
  public record NoveltyMode(
      String mode,
      Integer rotationSize,
      Integer maxConsecutiveSame,
      Integer weeklyUniqueMinimum,
      Integer newPerWeek) {}

  // ---- Cooking contexts ----

  public record CookingContexts(Map<String, CookingContext> byContext) {}

  public record CookingContext(
      @Min(0) int maxTimeMins,
      String complexity,
      List<String> preferredStyles,
      IngredientCountRange preferredIngredientCount,
      String notes,
      String source,
      String frequency) {}

  public record IngredientCountRange(@Min(0) int min, @Min(0) int max) {}

  // ---- Batch cooking ----

  public record BatchCooking(
      List<PrepDay> prepDays,
      Map<String, Integer> maxLeftoverDays,
      String leftoverStrategy,
      FreezerTolerance freezerTolerance,
      boolean sameProteinSameDay,
      String parallelCookingTolerance) {}

  public record PrepDay(
      String day, String window, @Min(0) int maxSessionHours, @Min(0) int maxRecipes) {}

  public record FreezerTolerance(
      boolean acceptable, @Min(0) int maxFrozenMealsPerWeek, List<String> exclusions) {}

  // ---- Reheating preferences ----

  public record ReheatingPreferences(
      List<String> availableAtWork,
      List<String> availableAtHome,
      String preferredMethod,
      List<ReheatRule> exclusions,
      List<String> coldMealTolerance) {}

  public record ReheatRule(String category, String rule, String reason) {}

  // ---- Eating context ----

  public record EatingContext(Map<String, ContextEntry> bySlot) {}

  public record ContextEntry(String location, String format, List<String> constraints) {}

  // ---- Seasonal preferences ----

  public record SeasonalPreferences(Map<String, SeasonPolicy> bySeason) {}

  public record SeasonPolicy(List<String> leanToward, List<String> avoid) {}

  // ---- Meal-type preferences ----

  public record MealTypePreferences(Map<String, MealTypeRule> byType) {}

  public record MealTypeRule(
      String varietyTolerance, String complexityTolerance, List<String> staples, String notes) {}

  // ---- Accompaniments ----

  public record Accompaniments(BeveragePolicy beverages, SidesPolicy sides) {}

  public record BeveragePolicy(String withMeals, String morning, List<String> avoids) {}

  public record SidesPolicy(String notes) {}

  // ---- Grocery quality ----

  public record GroceryQualityPreferences(
      String organic,
      String freeRangeEggs,
      String freeRangeMeat,
      String brandedVsOwnLabel,
      String notes) {}

  // ---- Pantry tracking ----

  /** Project-wide pantry-on/off flag — consumed by planner, notification, provisions. */
  public record PantryTracking(boolean enabled) {}
}
