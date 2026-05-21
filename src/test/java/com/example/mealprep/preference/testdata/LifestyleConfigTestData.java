package com.example.mealprep.preference.testdata;

import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.Accompaniments;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.BatchCooking;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.BeveragePolicy;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.ContextEntry;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.CookingContext;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.CookingContexts;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.DayProfile;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.EatingContext;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.FreezerTolerance;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.GroceryQualityPreferences;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.IngredientCountRange;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealSchedule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealStructure;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTiming;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTypePreferences;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTypeRule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyMode;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyTolerance;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.PantryTracking;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.PrepDay;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.ReheatRule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.ReheatingPreferences;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.SeasonPolicy;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.SeasonalPreferences;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.SidesPolicy;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.SnackPolicy;
import java.util.List;
import java.util.Map;

/**
 * Test data builder for the preference module's Tier-3 lifestyle config. Provides one fully-
 * populated document (covers every section + nested record) plus a couple of minimal variants used
 * by the diff / validator tests.
 */
public final class LifestyleConfigTestData {

  private LifestyleConfigTestData() {}

  /** A fully-populated document — every section non-null. Used by the round-trip and diff tests. */
  public static LifestyleConfigDocument fullDocument() {
    return new LifestyleConfigDocument(
        new MealStructure(
            new DayProfile(
                List.of("breakfast", "lunch", "dinner"),
                new SnackPolicy(true, "fruit", "weekday afternoon")),
            new DayProfile(List.of("brunch", "dinner"), new SnackPolicy(false, null, null)),
            List.of(new LifestyleConfigDocument.RecurringSkip("FRIDAY", "lunch", "takeaway day"))),
        new MealTiming(
            new MealSchedule(Map.of("breakfast", "07:00-08:00", "dinner", "19:00-20:00")),
            "tight",
            null),
        new NoveltyTolerance(
            Map.of("dinner", new NoveltyMode("rotation", 8, null, null, null)),
            Map.of("default", 2),
            Map.of("salmon", "weekly")),
        new CookingContexts(
            Map.of(
                "weeknight",
                new CookingContext(
                    30,
                    "medium",
                    List.of("italian", "thai"),
                    new IngredientCountRange(3, 10),
                    "needs pantry items",
                    "habitual",
                    "5x/week"))),
        new BatchCooking(
            List.of(new PrepDay("SUNDAY", "10:00-12:00", 2, 4)),
            Map.of("default", 3, "fish", 1),
            "fridge-first",
            new FreezerTolerance(true, 2, List.of("leafy greens")),
            false,
            "moderate"),
        new ReheatingPreferences(
            List.of("microwave"),
            List.of("hob", "oven"),
            "hob",
            List.of(new ReheatRule("salads", "never", "texture")),
            List.of("sandwiches")),
        new EatingContext(
            Map.of("lunch", new ContextEntry("office", "container", List.of("one-handed")))),
        new SeasonalPreferences(
            Map.of("summer", new SeasonPolicy(List.of("light"), List.of("stew")))),
        new MealTypePreferences(
            Map.of(
                "breakfast",
                new MealTypeRule("low", "low", List.of("oats", "eggs"), "fast and predictable"))),
        new Accompaniments(
            new BeveragePolicy("water", "coffee", List.of("soda")),
            new SidesPolicy("seasonal salad with dinner")),
        new GroceryQualityPreferences(
            "preferred", "always", "preferred", "own-label-ok", "supermarket: Tesco"),
        new PantryTracking(true));
  }

  /** Variant: same as {@link #fullDocument} but flip pantry tracking off. */
  public static LifestyleConfigDocument fullDocumentWithPantryDisabled() {
    LifestyleConfigDocument d = fullDocument();
    return new LifestyleConfigDocument(
        d.mealStructure(),
        d.mealTiming(),
        d.noveltyTolerance(),
        d.cookingContexts(),
        d.batchCooking(),
        d.reheatingPreferences(),
        d.eatingContext(),
        d.seasonalPreferences(),
        d.mealTypePreferences(),
        d.accompaniments(),
        d.groceryQualityPreferences(),
        new PantryTracking(false));
  }

  /** Variant: only batchCooking section non-null, others null. */
  public static LifestyleConfigDocument batchCookingOnly() {
    return new LifestyleConfigDocument(
        null,
        null,
        null,
        null,
        new BatchCooking(
            List.of(new PrepDay("SATURDAY", "09:00-12:00", 3, 5)),
            Map.of("default", 4),
            "freezer-first",
            new FreezerTolerance(true, 3, List.of()),
            true,
            "high"),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static UpdateLifestyleConfigRequest updateRequest(
      LifestyleConfigDocument document, long expectedVersion) {
    return new UpdateLifestyleConfigRequest(document, expectedVersion);
  }
}
