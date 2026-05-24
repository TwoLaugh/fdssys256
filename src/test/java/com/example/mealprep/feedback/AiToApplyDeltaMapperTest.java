package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.feedback.ai.internal.AiToApplyDeltaMapper;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiToApplyDeltaMapper} — the load-bearing AI-shape → wire-shape seam. Covers
 * all eight ops, the path-vocabulary translation, payload synthesis, and the drop-unresolvable
 * behaviour.
 */
class AiToApplyDeltaMapperTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 5, 23);
  private final Clock clock =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AiToApplyDeltaMapper mapper = new AiToApplyDeltaMapper(objectMapper, clock);

  @Test
  void toApplyDeltas_addIngredient_synthesisesTypedIngredientPreferenceWithFeedbackProvenance() {
    AiTasteProfileDelta.Add ai =
        new AiTasteProfileDelta.Add(
            "likes.ingredients",
            "prawns",
            "in quick high-heat preps",
            "f1",
            "single explicit positive",
            AiTasteProfileDelta.Confidence.MEDIUM);

    List<TasteProfileDelta> out = mapper.toApplyDeltas(List.of(ai));

    assertThat(out).hasSize(1);
    assertThat(out.get(0)).isInstanceOf(TasteProfileDelta.Add.class);
    TasteProfileDelta.Add add = (TasteProfileDelta.Add) out.get(0);
    assertThat(add.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(add.item().get("item").asText()).isEqualTo("prawns");
    assertThat(add.item().get("evidenceCount").asInt()).isEqualTo(1);
    assertThat(add.item().get("lastSignal").asText()).isEqualTo(TODAY.toString());
    assertThat(add.item().get("source").asText())
        .isEqualTo(IngredientPreferenceSource.FEEDBACK.name());
  }

  @Test
  void toApplyDeltas_addFlavourNote_stringListPath_usesTextNodeItem() {
    AiTasteProfileDelta.Add ai =
        new AiTasteProfileDelta.Add(
            "likes.flavour_notes",
            "spicy",
            null,
            "f1",
            "two events agree",
            AiTasteProfileDelta.Confidence.HIGH);

    TasteProfileDelta.Add add = (TasteProfileDelta.Add) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(add.fieldPath()).isEqualTo("flavourPreferences.likes");
    assertThat(add.item().isTextual()).isTrue();
    assertThat(add.item().asText()).isEqualTo("spicy");
  }

  @Test
  void toApplyDeltas_remove_mapsItemToItemKey() {
    AiTasteProfileDelta.Remove ai =
        new AiTasteProfileDelta.Remove("dislikes.ingredients", "olives", "f1", "explicit delete");

    TasteProfileDelta.Remove remove =
        (TasteProfileDelta.Remove) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(remove.fieldPath()).isEqualTo("ingredientPreferences.disliked");
    assertThat(remove.itemKey()).isEqualTo("olives");
  }

  @Test
  void toApplyDeltas_update_wrapsNewNotesIntoNotesPatch() {
    AiTasteProfileDelta.Update ai =
        new AiTasteProfileDelta.Update(
            "likes.ingredients", "chicken", "prefers char-grilled", "f1", "nuance added");

    TasteProfileDelta.Update update =
        (TasteProfileDelta.Update) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(update.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(update.itemKey()).isEqualTo("chicken");
    assertThat(update.patch().get("notes").asText()).isEqualTo("prefers char-grilled");
  }

  @Test
  void toApplyDeltas_archive_freeTextReasonFallsBackToStale() {
    AiTasteProfileDelta.Archive ai =
        new AiTasteProfileDelta.Archive(
            "likes.ingredients", "coriander", "user has gone off it", "f1", "explicit move-away");

    TasteProfileDelta.Archive archive =
        (TasteProfileDelta.Archive) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(archive.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(archive.itemKey()).isEqualTo("coriander");
    assertThat(archive.reason()).isEqualTo(ArchiveReason.STALE.name());
  }

  @Test
  void toApplyDeltas_archive_exactEnumNameHonoured() {
    AiTasteProfileDelta.Archive ai =
        new AiTasteProfileDelta.Archive(
            "likes.flavour_notes", "smoky", "TOKEN_PRESSURE", "f1", "evicted");

    TasteProfileDelta.Archive archive =
        (TasteProfileDelta.Archive) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(archive.reason()).isEqualTo(ArchiveReason.TOKEN_PRESSURE.name());
  }

  @Test
  void toApplyDeltas_rePromote_movesArchivedKeyToItemKeyAndResolvesFieldPath() {
    AiTasteProfileDelta.RePromote ai =
        new AiTasteProfileDelta.RePromote(
            "olives_arch_2025", "likes.ingredients", "f1", "recanted");

    TasteProfileDelta.RePromote rePromote =
        (TasteProfileDelta.RePromote) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(rePromote.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(rePromote.itemKey()).isEqualTo("olives_arch_2025");
  }

  @Test
  void toApplyDeltas_promoteExperiment_mapsHypothesisIdAndSynthesisesItem() {
    AiTasteProfileDelta.PromoteExperiment ai =
        new AiTasteProfileDelta.PromoteExperiment(
            "bitter+sweet works", "likes.ingredients", "rocket", "f1", "confirmed");

    TasteProfileDelta.PromoteExperiment promote =
        (TasteProfileDelta.PromoteExperiment) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(promote.hypothesis()).isEqualTo("bitter+sweet works");
    assertThat(promote.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(promote.promotedItem().get("item").asText()).isEqualTo("rocket");
    assertThat(promote.promotedItem().get("source").asText())
        .isEqualTo(IngredientPreferenceSource.FEEDBACK.name());
  }

  @Test
  void toApplyDeltas_discardExperiment_mapsHypothesisId() {
    AiTasteProfileDelta.DiscardExperiment ai =
        new AiTasteProfileDelta.DiscardExperiment("rocket+honey", "f1", "too weird");

    TasteProfileDelta.DiscardExperiment discard =
        (TasteProfileDelta.DiscardExperiment) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(discard.hypothesis()).isEqualTo("rocket+honey");
  }

  @Test
  void toApplyDeltas_updateNotes_mapsFlavourNotesPath() {
    AiTasteProfileDelta.UpdateNotes ai =
        new AiTasteProfileDelta.UpdateNotes(
            "flavourPreferences.notes", "leans savoury-forward", "f1", "meta annotation");

    TasteProfileDelta.UpdateNotes notes =
        (TasteProfileDelta.UpdateNotes) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(notes.fieldPath()).isEqualTo("flavourPreferences.notes");
    assertThat(notes.notes()).isEqualTo("leans savoury-forward");
  }

  @Test
  void toApplyDeltas_unresolvablePromptPath_isDroppedNotRejected() {
    AiTasteProfileDelta.Add good =
        new AiTasteProfileDelta.Add(
            "likes.ingredients", "tofu", null, "f1", "ok", AiTasteProfileDelta.Confidence.MEDIUM);
    AiTasteProfileDelta.Add bad =
        new AiTasteProfileDelta.Add(
            "nonsense.path", "garbage", null, "f2", "??", AiTasteProfileDelta.Confidence.LOW);

    List<TasteProfileDelta> out = mapper.toApplyDeltas(List.of(good, bad));

    // The bad op is silently dropped; the good op survives. Order preserved.
    assertThat(out).hasSize(1);
    assertThat(((TasteProfileDelta.Add) out.get(0)).fieldPath())
        .isEqualTo("ingredientPreferences.favourites");
  }

  @Test
  void toApplyDeltas_documentVocabularyPassesThroughUnchanged() {
    // A path already in document vocabulary (the applier's own) is honoured as-is.
    AiTasteProfileDelta.Add ai =
        new AiTasteProfileDelta.Add(
            "cuisinePreferences.favourites",
            "Korean",
            null,
            "f1",
            "ok",
            AiTasteProfileDelta.Confidence.MEDIUM);

    TasteProfileDelta.Add add = (TasteProfileDelta.Add) mapper.toApplyDeltas(List.of(ai)).get(0);

    assertThat(add.fieldPath()).isEqualTo("cuisinePreferences.favourites");
    assertThat(add.item().asText()).isEqualTo("Korean");
  }

  @Test
  void toApplyDeltas_emptyAndNull_returnEmpty() {
    assertThat(mapper.toApplyDeltas(List.of())).isEmpty();
    assertThat(mapper.toApplyDeltas(null)).isEmpty();
  }

  @Test
  void toApplyDeltas_translatesAllPromptPathVocabulary() {
    assertThat(addPathFor("likes.cuisines")).isEqualTo("cuisinePreferences.favourites");
    assertThat(addPathFor("dislikes.cuisines")).isEqualTo("cuisinePreferences.lessPreferred");
    assertThat(addPathFor("likes.cooking_methods"))
        .isEqualTo("cookingPreferences.preferredMethods");
    assertThat(addPathFor("dislikes.cooking_methods"))
        .isEqualTo("cookingPreferences.dislikedMethods");
    assertThat(addPathFor("dislikes.flavour_notes")).isEqualTo("flavourPreferences.dislikes");
  }

  /** Map an Add against {@code promptPath} and return the resolved wire fieldPath. */
  private String addPathFor(String promptPath) {
    AiTasteProfileDelta.Add ai =
        new AiTasteProfileDelta.Add(
            promptPath, "x", null, "f1", "r", AiTasteProfileDelta.Confidence.MEDIUM);
    return ((TasteProfileDelta.Add) mapper.toApplyDeltas(List.of(ai)).get(0)).fieldPath();
  }
}
