package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Pure JSON round-trip for {@link LifestyleConfigDocument}. No Spring, no DB. Asserts that every
 * section serialises and deserialises losslessly so the JSONB hydration path is safe.
 */
class LifestyleConfigDocumentRoundTripTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fullyPopulatedDocument_roundTrips_lossless() throws Exception {
    LifestyleConfigDocument original = LifestyleConfigTestData.fullDocument();

    String json = objectMapper.writeValueAsString(original);
    LifestyleConfigDocument decoded = objectMapper.readValue(json, LifestyleConfigDocument.class);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void emptyDocument_roundTrips_lossless() throws Exception {
    LifestyleConfigDocument original = LifestyleConfigDocument.empty();

    String json = objectMapper.writeValueAsString(original);
    LifestyleConfigDocument decoded = objectMapper.readValue(json, LifestyleConfigDocument.class);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void unicodeNotes_areLossless() throws Exception {
    LifestyleConfigDocument base = LifestyleConfigTestData.fullDocument();
    String emoji = "weekend brunch 🥞 — needs gluten-free";
    LifestyleConfigDocument withEmoji =
        new LifestyleConfigDocument(
            base.mealStructure(),
            new LifestyleConfigDocument.MealTiming(
                base.mealTiming().preferredSchedule(), base.mealTiming().flexibility(), emoji),
            base.noveltyTolerance(),
            base.cookingContexts(),
            base.batchCooking(),
            base.reheatingPreferences(),
            base.eatingContext(),
            base.seasonalPreferences(),
            base.mealTypePreferences(),
            base.accompaniments(),
            base.groceryQualityPreferences(),
            base.pantryTracking());

    String json = objectMapper.writeValueAsString(withEmoji);
    LifestyleConfigDocument decoded = objectMapper.readValue(json, LifestyleConfigDocument.class);
    assertThat(decoded.mealTiming().notes()).isEqualTo(emoji);
  }

  @Test
  void treeProjectionIsStructurallyStable() throws Exception {
    LifestyleConfigDocument doc = LifestyleConfigTestData.fullDocument();
    JsonNode tree1 = objectMapper.valueToTree(doc);
    JsonNode tree2 = objectMapper.valueToTree(doc);
    assertThat(tree1.equals(tree2)).isTrue();
  }
}
