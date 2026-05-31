package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CookingPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CuisinePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.FlavourPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.HouseholdContext;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.PortionStyle;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.SoftConstraints;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.SoftIntolerance;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TexturePreferences;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.entity.SkillLevel;
import com.example.mealprep.preference.domain.service.internal.TasteProfileEmbeddingInputBuilder;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the pure {@code compose(document)} serialisation in {@link
 * TasteProfileEmbeddingInputBuilder} (preference-5). The DB-loading {@code loadAndCompose} is
 * exercised by the IT; here we assert the serialisation is deterministic, label-prefixed, and
 * excludes volatile/non-semantic fields (versions, cursors, evidence counts, dates) so an
 * evidence-count-only delta does not change the embedding input.
 */
class TasteProfileEmbeddingInputBuilderTest {

  private final TasteProfileEmbeddingInputBuilder builder =
      new TasteProfileEmbeddingInputBuilder(null);

  @Test
  void nullDocument_returnsEmpty() {
    assertThat(builder.compose(null)).isEmpty();
  }

  @Test
  void emptyDocument_returnsEmpty() {
    assertThat(builder.compose(TasteProfileDocument.empty(LocalDate.of(2026, 5, 31)))).isEmpty();
  }

  @Test
  void populatedDocument_isLabelledAndDeterministic() {
    TasteProfileDocument doc = populated(3);
    String first = builder.compose(doc);
    String second = builder.compose(doc);

    assertThat(first).isEqualTo(second); // deterministic
    assertThat(first)
        .contains("flavour likes: smoky, umami")
        .contains("texture dislikes: slimy")
        .contains("ingredient favourites: garlic, lemon")
        .contains("cuisine favourites: thai, vietnamese")
        .contains("skill level: ADVANCED")
        .contains("soft intolerances: onion")
        .contains("household notes: kid-friendly please");
  }

  @Test
  void evidenceCountChange_doesNotChangeInput() {
    // Same items, different evidence counts/dates → identical embedding input (cache-friendly).
    assertThat(builder.compose(populated(3))).isEqualTo(builder.compose(populated(99)));
  }

  private static TasteProfileDocument populated(int evidenceCount) {
    return new TasteProfileDocument(
        LocalDate.of(2026, 5, 31),
        4,
        evidenceCount,
        "feedback-123",
        new SoftConstraints(List.of(new SoftIntolerance("onion", "mild", "bloating"))),
        new FlavourPreferences(List.of("smoky", "umami"), List.of("bitter"), "loves depth"),
        new TexturePreferences(List.of("crispy"), List.of("slimy")),
        new IngredientPreferences(
            List.of(
                new IngredientPreference(
                    "garlic",
                    evidenceCount,
                    LocalDate.of(2026, 1, 1),
                    IngredientPreferenceSource.FEEDBACK),
                new IngredientPreference(
                    "lemon",
                    evidenceCount,
                    LocalDate.of(2026, 2, 2),
                    IngredientPreferenceSource.INFERRED)),
            List.of(),
            List.of(),
            List.of()),
        new CuisinePreferences(List.of("thai", "vietnamese"), List.of(), List.of(), null),
        new CookingPreferences(SkillLevel.ADVANCED, List.of("stir-fry"), List.of("deep-fry")),
        new PortionStyle("generous", null),
        new HouseholdContext(List.of(), "kid-friendly please"),
        List.of(),
        List.of(),
        List.of(),
        List.of("prefers one-pot meals on weeknights"));
  }
}
