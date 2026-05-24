package com.example.mealprep.feedback.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit wire-contract coverage for the {@code PREFERENCE_DELTA_UPDATE} structured-output shape
 * ({@link TasteProfileDeltaResponse} carrying polymorphic {@link AiTasteProfileDelta} entries).
 *
 * <p>No Spring context and no DB: this deserialises a REALISTIC raw JSON string — the kind of body
 * the model emits per {@code lld/prompts/01-taste-profile-delta.md} — through a plain {@link
 * ObjectMapper} (the polymorphic binding is driven entirely by the
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} annotations on the DTO, not by any global Jackson
 * config, so a default mapper mirrors the app's deserialisation faithfully). It asserts field
 * names, nesting, the {@code "type"} discriminator, and the {@code Confidence} enum all map
 * correctly — i.e. that the JSON the E2E suite seeds via {@code E2eAiStubController} / {@code
 * TestAiService#registerJson} will bind to the domain type exactly as prod's {@code
 * AiServiceImpl#deserialise} would.
 */
class TasteProfileDeltaResponseFixtureTest {

  // Default mapper — same binding the app uses for these annotation-driven polymorphic records.
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void deserialises_addDelta_withConfidenceEnum_andDiscriminator() throws Exception {
    // A realistic single-Add response (the "I love prawns" shape from PreferenceDeltaPipelineIT).
    String json =
        """
        {
          "deltas": [
            {
              "type": "Add",
              "fieldPath": "likes.ingredients",
              "item": "prawns",
              "notes": "especially in quick high-heat preparations",
              "evidenceFeedbackId": "f1e2d3c4-0000-4000-8000-000000000001",
              "reasoning": "single explicit positive statement about prawns",
              "confidence": "MEDIUM"
            }
          ],
          "overallReasoning": "added prawns to likes from explicit feedback",
          "warnings": []
        }
        """;

    TasteProfileDeltaResponse response =
        objectMapper.readValue(json, TasteProfileDeltaResponse.class);

    assertThat(response.overallReasoning())
        .isEqualTo("added prawns to likes from explicit feedback");
    assertThat(response.warnings()).isEmpty();
    assertThat(response.deltas()).hasSize(1);

    assertThat(response.deltas().get(0)).isInstanceOf(AiTasteProfileDelta.Add.class);
    AiTasteProfileDelta.Add add = (AiTasteProfileDelta.Add) response.deltas().get(0);
    assertThat(add.fieldPath()).isEqualTo("likes.ingredients");
    assertThat(add.item()).isEqualTo("prawns");
    assertThat(add.notes()).isEqualTo("especially in quick high-heat preparations");
    assertThat(add.evidenceFeedbackId()).isEqualTo("f1e2d3c4-0000-4000-8000-000000000001");
    assertThat(add.reasoning()).isEqualTo("single explicit positive statement about prawns");
    assertThat(add.confidence()).isEqualTo(AiTasteProfileDelta.Confidence.MEDIUM);
  }

  @Test
  void deserialises_mixedDeltaTypes_eachToItsSubtype() throws Exception {
    // Exercises several discriminator branches in one body: Add, Archive, Update, and the
    // DiscardExperiment subtype whose fieldPath() is a fixed override (not present in the JSON).
    String json =
        """
        {
          "deltas": [
            {
              "type": "Add",
              "fieldPath": "likes.cuisines",
              "item": "thai",
              "notes": null,
              "evidenceFeedbackId": "00000000-0000-4000-8000-000000000010",
              "reasoning": "ordered thai three weeks running",
              "confidence": "HIGH"
            },
            {
              "type": "Archive",
              "fieldPath": "likes.ingredients",
              "item": "cilantro",
              "archiveReason": "user now reports it tastes soapy",
              "evidenceFeedbackId": "00000000-0000-4000-8000-000000000011",
              "reasoning": "explicit reversal"
            },
            {
              "type": "Update",
              "fieldPath": "dislikes.textures",
              "item": "mushy",
              "newNotes": "only objects to mushy in savoury dishes",
              "evidenceFeedbackId": "00000000-0000-4000-8000-000000000012",
              "reasoning": "clarified scope of the dislike"
            },
            {
              "type": "DiscardExperiment",
              "hypothesisId": "exp-42",
              "evidenceFeedbackId": "00000000-0000-4000-8000-000000000013",
              "reasoning": "hypothesis disproven over the trial window"
            }
          ],
          "overallReasoning": "batch of four operations from this week's feedback",
          "warnings": ["one archive op this batch"]
        }
        """;

    TasteProfileDeltaResponse response =
        objectMapper.readValue(json, TasteProfileDeltaResponse.class);

    assertThat(response.deltas()).hasSize(4);
    assertThat(response.warnings()).containsExactly("one archive op this batch");

    AiTasteProfileDelta.Add add = (AiTasteProfileDelta.Add) response.deltas().get(0);
    assertThat(add.item()).isEqualTo("thai");
    assertThat(add.notes()).isNull();
    assertThat(add.confidence()).isEqualTo(AiTasteProfileDelta.Confidence.HIGH);

    AiTasteProfileDelta.Archive archive = (AiTasteProfileDelta.Archive) response.deltas().get(1);
    assertThat(archive.fieldPath()).isEqualTo("likes.ingredients");
    assertThat(archive.item()).isEqualTo("cilantro");
    assertThat(archive.archiveReason()).isEqualTo("user now reports it tastes soapy");

    AiTasteProfileDelta.Update update = (AiTasteProfileDelta.Update) response.deltas().get(2);
    assertThat(update.fieldPath()).isEqualTo("dislikes.textures");
    assertThat(update.newNotes()).isEqualTo("only objects to mushy in savoury dishes");

    AiTasteProfileDelta.DiscardExperiment discard =
        (AiTasteProfileDelta.DiscardExperiment) response.deltas().get(3);
    assertThat(discard.hypothesisId()).isEqualTo("exp-42");
    // The subtype hard-codes fieldPath() — it must resolve even though the JSON omits it.
    assertThat(discard.fieldPath()).isEqualTo("activeExperiments");
  }

  @Test
  void deserialises_emptyDeltas_viaNullSafeAccessors() throws Exception {
    // The model warrants no change: deltas + warnings omitted entirely. The DTO's null-safe
    // accessors must surface empty lists rather than NPE.
    String json =
        """
        {
          "overallReasoning": "no actionable preference signal in this feedback"
        }
        """;

    TasteProfileDeltaResponse response =
        objectMapper.readValue(json, TasteProfileDeltaResponse.class);

    assertThat(response.deltas()).isEmpty();
    assertThat(response.warnings()).isEmpty();
    assertThat(response.overallReasoning())
        .isEqualTo("no actionable preference signal in this feedback");
  }

  @Test
  void rejects_unknownDiscriminator() {
    // A "type" the @JsonSubTypes registry doesn't know must fail to bind — proves the discriminator
    // is genuinely enforced, not silently defaulted.
    String json =
        """
        {
          "deltas": [
            { "type": "Teleport", "fieldPath": "likes.ingredients" }
          ],
          "overallReasoning": "x"
        }
        """;

    assertThatThrownBy(() -> objectMapper.readValue(json, TasteProfileDeltaResponse.class))
        .isInstanceOf(InvalidTypeIdException.class);
  }
}
