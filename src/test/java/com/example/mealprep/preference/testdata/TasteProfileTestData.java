package com.example.mealprep.preference.testdata;

import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.ActiveExperiment;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CookingPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CuisinePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.FlavourPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.HouseholdContext;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.PortionStyle;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.RecipeRecommendation;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.SoftConstraints;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.SoftIntolerance;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TexturePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TrendingIngredient;
import com.example.mealprep.preference.domain.entity.ExperimentStatus;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.entity.SkillLevel;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Test Data Builder for the taste profile aggregate, its document tree, and the manual-override
 * request. All defaults satisfy the validator constraints so callers tweak only the field under
 * test.
 */
public final class TasteProfileTestData {

  private TasteProfileTestData() {}

  public static TasteProfile aggregate(UUID userId) {
    return TasteProfile.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .document(populatedDocument(1))
        .documentVersion(1)
        .feedbackCursor(null)
        .basedOnFeedbackCount(0)
        .lastDeltaAppliedAt(null)
        .lastTokenEstimate(null)
        .tasteVectorStatus(TasteVectorStatus.PENDING)
        .tasteVectorDocVersion(null)
        .tasteVectorModelId(null)
        .tasteVectorEmbeddedAt(null)
        .optimisticVersion(0L)
        .createdAt(Instant.parse("2026-05-20T10:00:00Z"))
        .updatedAt(Instant.parse("2026-05-20T10:00:00Z"))
        .build();
  }

  public static TasteProfileDocument populatedDocument(int version) {
    LocalDate today = LocalDate.parse("2026-05-20");
    return new TasteProfileDocument(
        today,
        version,
        7,
        "f-42",
        new SoftConstraints(
            List.of(new SoftIntolerance("mushrooms", "mild", "headaches in volume"))),
        new FlavourPreferences(
            List.of("umami", "smoky"), List.of("overly sweet"), "prefers savoury"),
        new TexturePreferences(List.of("crispy", "tender"), List.of("slimy")),
        new IngredientPreferences(
            List.of(
                new IngredientPreference("salmon", 12, today, IngredientPreferenceSource.FEEDBACK)),
            List.of(
                new IngredientPreference("kale", 4, today, IngredientPreferenceSource.INFERRED)),
            List.of(new TrendingIngredient("kimchi", 3, today)),
            List.of(new TrendingIngredient("aubergine", 2, today))),
        new CuisinePreferences(
            List.of("japanese"), List.of("italian"), List.of("indian"), "no curries on weeknights"),
        new CookingPreferences(
            SkillLevel.INTERMEDIATE, List.of("roast", "stir-fry"), List.of("deep-fry")),
        new PortionStyle("standard", "side"),
        new HouseholdContext(List.of("spicy"), "kids prefer mild"),
        List.of(new RecipeRecommendation("Salmon teriyaki", "weeknight", "fast and liked")),
        List.of(new RecipeRecommendation("Beef stew", "weekend", "leftover staleness")),
        List.of(new ActiveExperiment("does spicier work?", ExperimentStatus.TESTING, 3, 1, today)),
        List.of("loves citrus in fish dishes"));
  }

  public static UpdateTasteProfileRequest updateRequest(long expectedVersion) {
    return new UpdateTasteProfileRequest(populatedDocument(0), expectedVersion);
  }

  public static UpdateTasteProfileRequest updateRequestWithDocument(
      TasteProfileDocument document, long expectedVersion) {
    return new UpdateTasteProfileRequest(document, expectedVersion);
  }
}
