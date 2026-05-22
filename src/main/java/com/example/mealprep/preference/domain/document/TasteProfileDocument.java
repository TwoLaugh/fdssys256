package com.example.mealprep.preference.domain.document;

import com.example.mealprep.preference.domain.entity.ExperimentStatus;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.entity.SkillLevel;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Canonical Java mirror of the taste-profile JSONB document. Owned by the preference module; the AI
 * never produces the whole document — it produces deltas applied against this shape by the
 * (deferred) delta-applier pipeline.
 *
 * <p>One nested record per HLD section. Leaf types are primitives, enums, {@link String}s, or
 * {@code List<String>}; deltas are typed separately (see {@code
 * preference.api.dto.TasteProfileDelta}). The top-level {@link #version} field mirrors the entity's
 * {@code documentVersion} and is bumped monotonically per delta-batch.
 *
 * <p>Validation: Jakarta annotations on size-bounded list fields are surfaced at the controller
 * layer when an inbound request carries this record (e.g. manual override). The AI delta pipeline
 * runs its own bounds-checks via {@code TasteProfileBudgetGuard} (deferred).
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record TasteProfileDocument(
    LocalDate lastUpdated,
    int version,
    int basedOnFeedbackCount,
    @Size(max = 64) String feedbackCursor,
    @Valid SoftConstraints softConstraints,
    @Valid FlavourPreferences flavourPreferences,
    @Valid TexturePreferences texturePreferences,
    @Valid IngredientPreferences ingredientPreferences,
    @Valid CuisinePreferences cuisinePreferences,
    @Valid CookingPreferences cookingPreferences,
    @Valid PortionStyle portionStyle,
    @Valid HouseholdContext householdContext,
    @Size(max = 50) List<@Valid RecipeRecommendation> recipesToRepeat,
    @Size(max = 50) List<@Valid RecipeRecommendation> recipesToAvoid,
    @Size(max = 20) List<@Valid ActiveExperiment> activeExperiments,
    @Size(max = 20) List<@Size(max = 512) String> learnedInsights) {

  // ---------------- soft constraints ----------------

  public record SoftConstraints(@Size(max = 30) List<@Valid SoftIntolerance> intolerances) {}

  public record SoftIntolerance(
      @Size(max = 64) String substance,
      @Size(max = 32) String severity,
      @Size(max = 255) String notes) {}

  // ---------------- flavour ----------------

  public record FlavourPreferences(
      @Size(max = 30) List<@Size(max = 64) String> likes,
      @Size(max = 30) List<@Size(max = 64) String> dislikes,
      @Size(max = 512) String notes) {}

  // ---------------- texture ----------------

  public record TexturePreferences(
      @Size(max = 30) List<@Size(max = 64) String> likes,
      @Size(max = 30) List<@Size(max = 64) String> dislikes) {}

  // ---------------- ingredient ----------------

  public record IngredientPreferences(
      @Size(max = 50) List<@Valid IngredientPreference> favourites,
      @Size(max = 50) List<@Valid IngredientPreference> disliked,
      @Size(max = 50) List<@Valid TrendingIngredient> trendingPositive,
      @Size(max = 50) List<@Valid TrendingIngredient> trendingNegative) {}

  public record IngredientPreference(
      @Size(max = 128) String item,
      int evidenceCount,
      LocalDate lastSignal,
      IngredientPreferenceSource source) {}

  public record TrendingIngredient(
      @Size(max = 128) String item, int evidenceCount, LocalDate firstSignal) {}

  // ---------------- cuisine ----------------

  public record CuisinePreferences(
      @Size(max = 30) List<@Size(max = 64) String> favourites,
      @Size(max = 30) List<@Size(max = 64) String> enjoys,
      @Size(max = 30) List<@Size(max = 64) String> lessPreferred,
      @Size(max = 512) String notes) {}

  // ---------------- cooking ----------------

  public record CookingPreferences(
      SkillLevel skillLevel,
      @Size(max = 30) List<@Size(max = 64) String> preferredMethods,
      @Size(max = 30) List<@Size(max = 64) String> dislikedMethods) {}

  // ---------------- portion ----------------

  public record PortionStyle(
      @Size(max = 64) String preference, @Size(max = 64) String saladMeals) {}

  // ---------------- household context ----------------

  public record HouseholdContext(
      @Size(max = 30) List<@Size(max = 128) String> individualOnlyPreferences,
      @Size(max = 512) String householdSuitableNotes) {}

  // ---------------- recipe & experiment ----------------

  public record RecipeRecommendation(
      @Size(max = 128) String name,
      @Size(max = 64) String suitableFor,
      @Size(max = 512) String reason) {}

  public record ActiveExperiment(
      @Size(max = 512) String hypothesis,
      ExperimentStatus status,
      int evidenceFor,
      int evidenceAgainst,
      LocalDate created) {}

  /** Empty-but-valid document for a freshly initialised profile. */
  public static TasteProfileDocument empty(LocalDate today) {
    return new TasteProfileDocument(
        today,
        1,
        0,
        null,
        new SoftConstraints(List.of()),
        new FlavourPreferences(List.of(), List.of(), null),
        new TexturePreferences(List.of(), List.of()),
        new IngredientPreferences(List.of(), List.of(), List.of(), List.of()),
        new CuisinePreferences(List.of(), List.of(), List.of(), null),
        new CookingPreferences(SkillLevel.INTERMEDIATE, List.of(), List.of()),
        new PortionStyle(null, null),
        new HouseholdContext(List.of(), null),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
