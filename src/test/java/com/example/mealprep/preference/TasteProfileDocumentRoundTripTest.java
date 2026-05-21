package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Verifies a fully-populated {@link TasteProfileDocument} survives Jackson serialise→deserialise
 * without losing any nested record. Unit test — no Spring context.
 */
class TasteProfileDocumentRoundTripTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void populatedDocument_roundTripsEqual() throws Exception {
    TasteProfileDocument original = TasteProfileTestData.populatedDocument(7);

    String json = objectMapper.writeValueAsString(original);
    TasteProfileDocument restored = objectMapper.readValue(json, TasteProfileDocument.class);

    assertThat(restored).isEqualTo(original);
  }

  @Test
  void emptyDocument_roundTripsEqual() throws Exception {
    TasteProfileDocument empty = TasteProfileDocument.empty(LocalDate.parse("2026-05-20"));

    String json = objectMapper.writeValueAsString(empty);
    TasteProfileDocument restored = objectMapper.readValue(json, TasteProfileDocument.class);

    assertThat(restored).isEqualTo(empty);
  }

  @Test
  void emptyFactory_buildsAllSectionsWithNonNullCollections() {
    TasteProfileDocument empty = TasteProfileDocument.empty(LocalDate.parse("2026-05-20"));

    assertThat(empty.softConstraints().intolerances()).isEmpty();
    assertThat(empty.flavourPreferences().likes()).isEmpty();
    assertThat(empty.texturePreferences().likes()).isEmpty();
    assertThat(empty.ingredientPreferences().favourites()).isEmpty();
    assertThat(empty.ingredientPreferences().disliked()).isEmpty();
    assertThat(empty.ingredientPreferences().trendingPositive()).isEmpty();
    assertThat(empty.ingredientPreferences().trendingNegative()).isEmpty();
    assertThat(empty.cuisinePreferences().favourites()).isEmpty();
    assertThat(empty.cookingPreferences().preferredMethods()).isEmpty();
    assertThat(empty.portionStyle()).isNotNull();
    assertThat(empty.householdContext().individualOnlyPreferences()).isEmpty();
    assertThat(empty.recipesToRepeat()).isEmpty();
    assertThat(empty.recipesToAvoid()).isEmpty();
    assertThat(empty.activeExperiments()).isEmpty();
    assertThat(empty.learnedInsights()).isEmpty();
    assertThat(empty.version()).isEqualTo(1);
  }
}
