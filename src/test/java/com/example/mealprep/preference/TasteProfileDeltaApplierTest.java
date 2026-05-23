package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.ActiveExperiment;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.ExperimentStatus;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.domain.service.internal.TasteProfileDeltaApplier;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit coverage for the deterministic {@link TasteProfileDeltaApplier.Default}: each of the eight
 * delta ops produces the expected immutable-document mutation, validation failures throw {@link
 * InvalidTasteProfileDeltaException}, the archive side-effects are driven for Archive / RePromote,
 * the anomaly WARN fires past the removal threshold, and op-order is honoured.
 *
 * <p>The {@link PreferenceArchiveUpdateService} is mocked (it is a real module collaborator, but
 * the applier's contract is just "calls it with the right request" — the archive's own behaviour is
 * covered by {@code PreferenceArchiveServiceImplTest}). The {@link ObjectMapper} is real
 * (deterministic, no IO).
 */
class TasteProfileDeltaApplierTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final LocalDate TODAY = LocalDate.parse("2026-05-20");

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private PreferenceArchiveUpdateService archiveUpdateService;
  private TasteProfileDeltaApplier.Default applier;

  @BeforeEach
  void setUp() {
    archiveUpdateService = Mockito.mock(PreferenceArchiveUpdateService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-20T10:00:00Z"), ZoneOffset.UTC);
    applier = new TasteProfileDeltaApplier.Default(objectMapper, archiveUpdateService, clock);
  }

  private TasteProfileDocument apply(TasteProfileDelta... deltas) {
    ApplyTasteProfileDeltasRequest request =
        new ApplyTasteProfileDeltasRequest(
            List.of(deltas), TasteProfileTrigger.BATCH, "feedback-1", "feedback-9", "cheap");
    return applier.apply(TasteProfileTestData.populatedDocument(1), request, USER_ID);
  }

  private JsonNode ingredient(String item, int evidence, String source) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("item", item);
    node.put("evidenceCount", evidence);
    node.put("lastSignal", "2026-05-01");
    node.put("source", source);
    return node;
  }

  // ---------------- Add ----------------

  @Test
  void add_appendsParsedIngredient_andBumpsVersion() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Add(
                "ingredientPreferences.disliked", ingredient("coriander", 3, "FEEDBACK")));

    assertThat(result.version()).isEqualTo(2);
    assertThat(result.ingredientPreferences().disliked())
        .extracting(IngredientPreference::item)
        .contains("coriander", "kale");
    assertThat(result.basedOnFeedbackCount()).isEqualTo(8); // base 7 + 1 delta
  }

  @Test
  void add_toStringList_appendsValue() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Add(
                "flavourPreferences.likes", objectMapper.getNodeFactory().textNode("tangy")));

    assertThat(result.flavourPreferences().likes()).contains("umami", "smoky", "tangy");
  }

  @Test
  void add_dedup_existingKey_isNoOpMerge_notDuplicate() {
    // "salmon" is already in favourites — re-adding must not duplicate it.
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Add(
                "ingredientPreferences.favourites", ingredient("salmon", 99, "FEEDBACK")));

    assertThat(result.ingredientPreferences().favourites())
        .extracting(IngredientPreference::item)
        .containsExactly("salmon");
    // Dedup is a no-op merge: the original evidence (12), not the incoming 99, is retained.
    assertThat(result.ingredientPreferences().favourites().get(0).evidenceCount()).isEqualTo(12);
  }

  @Test
  void add_toUnknownFieldPath_throws422() {
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.Add(
                        "bogus.path", objectMapper.getNodeFactory().textNode("x"))))
        .isInstanceOf(InvalidTasteProfileDeltaException.class)
        .hasMessageContaining("bogus.path");
  }

  // ---------------- Remove ----------------

  @Test
  void remove_dropsItem() {
    TasteProfileDocument result =
        apply(new TasteProfileDelta.Remove("ingredientPreferences.disliked", "kale"));

    assertThat(result.ingredientPreferences().disliked()).isEmpty();
  }

  @Test
  void remove_fromStringList_dropsValue() {
    TasteProfileDocument result =
        apply(new TasteProfileDelta.Remove("flavourPreferences.likes", "umami"));

    assertThat(result.flavourPreferences().likes()).containsExactly("smoky");
  }

  @Test
  void remove_nonExistentItem_throws422_andLeavesDocumentUnchanged() {
    assertThatThrownBy(
            () -> apply(new TasteProfileDelta.Remove("ingredientPreferences.disliked", "ghost")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class)
        .hasMessageContaining("ghost");
  }

  // ---------------- Update ----------------

  @Test
  void update_patchesMutableField_withoutChangingKey() {
    ObjectNode patch = objectMapper.createObjectNode();
    patch.put("evidenceCount", 42);
    TasteProfileDocument result =
        apply(new TasteProfileDelta.Update("ingredientPreferences.favourites", "salmon", patch));

    IngredientPreference salmon = result.ingredientPreferences().favourites().get(0);
    assertThat(salmon.item()).isEqualTo("salmon");
    assertThat(salmon.evidenceCount()).isEqualTo(42);
  }

  @Test
  void update_onStringListPath_throws422() {
    ObjectNode patch = objectMapper.createObjectNode();
    patch.put("evidenceCount", 1);
    assertThatThrownBy(
            () -> apply(new TasteProfileDelta.Update("flavourPreferences.likes", "umami", patch)))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
  }

  @Test
  void update_missingItem_throws422() {
    ObjectNode patch = objectMapper.createObjectNode();
    patch.put("evidenceCount", 1);
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.Update(
                        "ingredientPreferences.favourites", "ghost", patch)))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
  }

  // ---------------- UpdateNotes ----------------

  @Test
  void updateNotes_replacesNotesText() {
    TasteProfileDocument result =
        apply(new TasteProfileDelta.UpdateNotes("flavourPreferences.notes", "now loves heat"));

    assertThat(result.flavourPreferences().notes()).isEqualTo("now loves heat");
    // Other fields preserved.
    assertThat(result.flavourPreferences().likes()).contains("umami");
  }

  @Test
  void updateNotes_twoInOneBatch_throws422() {
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.UpdateNotes("flavourPreferences.notes", "a"),
                    new TasteProfileDelta.UpdateNotes("cuisinePreferences.notes", "b")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class)
        .hasMessageContaining("UpdateNotes");
  }

  @Test
  void updateNotes_unknownNotesPath_throws422() {
    assertThatThrownBy(
            () -> apply(new TasteProfileDelta.UpdateNotes("texturePreferences.notes", "x")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
  }

  // ---------------- PromoteExperiment ----------------

  @Test
  void promoteExperiment_flipsToPromoted_andAddsPromotedItemToTarget() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.PromoteExperiment(
                "does spicier work?",
                "ingredientPreferences.favourites",
                ingredient("chilli", 5, "FEEDBACK")));

    ActiveExperiment promoted =
        result.activeExperiments().stream()
            .filter(e -> e.hypothesis().equals("does spicier work?"))
            .findFirst()
            .orElseThrow();
    assertThat(promoted.status()).isEqualTo(ExperimentStatus.PROMOTED);
    assertThat(result.ingredientPreferences().favourites())
        .extracting(IngredientPreference::item)
        .contains("chilli");
  }

  @Test
  void promoteExperiment_noMatchingTestingExperiment_throws422() {
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.PromoteExperiment(
                        "no such hypothesis",
                        "ingredientPreferences.favourites",
                        ingredient("x", 1, "FEEDBACK"))))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
  }

  // ---------------- DiscardExperiment ----------------

  @Test
  void discardExperiment_retainsWithDiscardedStatus() {
    TasteProfileDocument result =
        apply(new TasteProfileDelta.DiscardExperiment("does spicier work?"));

    ActiveExperiment discarded =
        result.activeExperiments().stream()
            .filter(e -> e.hypothesis().equals("does spicier work?"))
            .findFirst()
            .orElseThrow();
    assertThat(discarded.status()).isEqualTo(ExperimentStatus.DISCARDED);
    // Retained as history, not dropped.
    assertThat(result.activeExperiments()).hasSize(1);
  }

  @Test
  void discardExperiment_noMatch_throws422() {
    assertThatThrownBy(() -> apply(new TasteProfileDelta.DiscardExperiment("ghost")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
  }

  // ---------------- Archive ----------------

  @Test
  void archive_removesFromLiveDocument_andWritesArchiveRowVerbatim() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Archive(
                "ingredientPreferences.favourites", "salmon", "LOW_EVIDENCE"));

    assertThat(result.ingredientPreferences().favourites()).isEmpty();

    ArgumentCaptor<ArchiveItemRequest> captor = ArgumentCaptor.forClass(ArchiveItemRequest.class);
    verify(archiveUpdateService).archiveItem(eq(USER_ID), captor.capture());
    ArchiveItemRequest req = captor.getValue();
    assertThat(req.fieldPath()).isEqualTo("ingredientPreferences.favourites");
    assertThat(req.itemKey()).isEqualTo("salmon");
    assertThat(req.reason()).isEqualTo(ArchiveReason.LOW_EVIDENCE);
    assertThat(req.evidenceCount()).isEqualTo(12);
    assertThat(req.itemPayload().get("item").asText()).isEqualTo("salmon");
    assertThat(req.lastSignalAt()).isEqualTo(TODAY);
  }

  @Test
  void archive_unknownReason_throws422_andSkipsArchiveWrite() {
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.Archive(
                        "ingredientPreferences.favourites", "salmon", "NOT_A_REASON")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
    verifyNoInteractions(archiveUpdateService);
  }

  // ---------------- RePromote ----------------

  @Test
  void rePromote_happyPath_restoresArchivedPayloadVerbatim() {
    JsonNode payload = ingredient("chickpeas", 12, "FEEDBACK");
    when(archiveUpdateService.markRePromoted(
            USER_ID, "ingredientPreferences.favourites", "chickpeas"))
        .thenReturn(
            new PreferenceArchiveEntryDto(
                UUID.randomUUID(),
                USER_ID,
                "ingredientPreferences.favourites",
                "chickpeas",
                payload,
                12,
                LocalDate.parse("2026-05-01"),
                Instant.parse("2026-05-10T10:00:00Z"),
                ArchiveReason.LOW_EVIDENCE,
                Instant.parse("2026-05-20T10:00:00Z")));

    TasteProfileDocument result =
        apply(new TasteProfileDelta.RePromote("ingredientPreferences.favourites", "chickpeas"));

    verify(archiveUpdateService)
        .markRePromoted(USER_ID, "ingredientPreferences.favourites", "chickpeas");
    IngredientPreference restored =
        result.ingredientPreferences().favourites().stream()
            .filter(i -> i.item().equals("chickpeas"))
            .findFirst()
            .orElseThrow();
    // Restored verbatim with its preserved evidence (12), not a fresh Add.
    assertThat(restored.evidenceCount()).isEqualTo(12);
    assertThat(restored.source()).isEqualTo(IngredientPreferenceSource.FEEDBACK);
  }

  @Test
  void rePromote_noMatchingArchiveEntry_fallsBackToAdd_doesNotReject() {
    when(archiveUpdateService.markRePromoted(any(), any(), any()))
        .thenThrow(
            new PreferenceArchiveEntryNotFoundException(
                USER_ID, "ingredientPreferences.favourites", "newthing"));

    TasteProfileDocument result =
        apply(new TasteProfileDelta.RePromote("ingredientPreferences.favourites", "newthing"));

    // Batch NOT rejected: a minimal stub item keyed on "newthing" was added.
    assertThat(result.ingredientPreferences().favourites())
        .extracting(IngredientPreference::item)
        .contains("newthing");
  }

  @Test
  void rePromote_toStringList_fallback_addsKeyText() {
    when(archiveUpdateService.markRePromoted(any(), any(), any()))
        .thenThrow(
            new PreferenceArchiveEntryNotFoundException(
                USER_ID, "flavourPreferences.likes", "zest"));

    TasteProfileDocument result =
        apply(new TasteProfileDelta.RePromote("flavourPreferences.likes", "zest"));

    assertThat(result.flavourPreferences().likes()).contains("zest");
  }

  // ---------------- anomaly detection ----------------

  @Test
  void fourArchiveOps_appliesSuccessfully_andDoesNotThrow() {
    // 4 archive ops > the threshold of 3 → WARN logged, NOT thrown.
    when(archiveUpdateService.archiveItem(any(), any())).thenReturn(null);
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Archive("flavourPreferences.likes", "umami", "STALE"),
            new TasteProfileDelta.Archive("flavourPreferences.likes", "smoky", "STALE"),
            new TasteProfileDelta.Archive("flavourPreferences.dislikes", "overly sweet", "STALE"),
            new TasteProfileDelta.Archive("texturePreferences.dislikes", "slimy", "STALE"));

    assertThat(result.version()).isEqualTo(2);
    verify(archiveUpdateService, Mockito.times(4)).archiveItem(eq(USER_ID), any());
  }

  // ---------------- order sensitivity ----------------

  @Test
  void addThenRemove_sameItem_netsToAbsent() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Add(
                "ingredientPreferences.disliked", ingredient("turnip", 1, "FEEDBACK")),
            new TasteProfileDelta.Remove("ingredientPreferences.disliked", "turnip"));

    assertThat(result.ingredientPreferences().disliked())
        .extracting(IngredientPreference::item)
        .doesNotContain("turnip");
  }

  @Test
  void removeThenAdd_sameItem_netsToPresent() {
    TasteProfileDocument result =
        apply(
            new TasteProfileDelta.Remove("ingredientPreferences.disliked", "kale"),
            new TasteProfileDelta.Add(
                "ingredientPreferences.disliked", ingredient("kale", 9, "FEEDBACK")));

    assertThat(result.ingredientPreferences().disliked())
        .extracting(IngredientPreference::item)
        .contains("kale");
    assertThat(result.ingredientPreferences().disliked().get(0).evidenceCount()).isEqualTo(9);
  }

  // ---------------- whole-batch rejection (no partial state) ----------------

  @Test
  void invalidOpInBatch_rejectsWholeBatch_noArchiveWrite() {
    // First op is a valid Archive; second op targets a missing item → whole batch rejected before
    // any apply, so the archive write must NOT have happened.
    assertThatThrownBy(
            () ->
                apply(
                    new TasteProfileDelta.Archive(
                        "ingredientPreferences.favourites", "salmon", "STALE"),
                    new TasteProfileDelta.Remove("ingredientPreferences.disliked", "ghost")))
        .isInstanceOf(InvalidTasteProfileDeltaException.class);
    verify(archiveUpdateService, never()).archiveItem(any(), any());
  }

  @Test
  void over50Deltas_throws422() {
    TasteProfileDelta[] many = new TasteProfileDelta[51];
    for (int i = 0; i < 51; i++) {
      many[i] =
          new TasteProfileDelta.Add(
              "learnedInsights", objectMapper.getNodeFactory().textNode("insight-" + i));
    }
    assertThatThrownBy(() -> apply(many))
        .isInstanceOf(InvalidTasteProfileDeltaException.class)
        .hasMessageContaining("50");
  }
}
