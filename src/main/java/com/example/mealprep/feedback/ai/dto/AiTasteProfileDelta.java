package com.example.mealprep.feedback.ai.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * AI-response shape of a single taste-profile delta, produced by {@code
 * PreferenceTasteProfileDeltaTask} (preference-01g). This is the <b>generate</b> shape per {@code
 * lld/prompts/01-taste-profile-delta.md:57-93} — it carries the model's justification fields
 * ({@code evidenceFeedbackId}, {@code reasoning}, and {@code confidence} on {@code Add}) that the
 * deterministic <b>apply</b> shape ({@code preference.api.dto.TasteProfileDelta}) does not.
 *
 * <p><b>Two distinct delta shapes.</b> Keep this AI-response type in the feedback module so it
 * never collides with the preference wire DTO. {@code AiToApplyDeltaMapper} converts AI → wire; the
 * justification fields are dropped from the apply payload and recorded in {@code overallReasoning}
 * / the version-snapshot context instead.
 *
 * <p>Jackson polymorphism keys on the {@code type} property (matching the in-prompt examples'
 * {@code "type": "Add"} discriminator), so {@code TasteProfileDeltaResponse} round-trips through
 * the dispatcher's structured-output binding.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AiTasteProfileDelta.Add.class, name = "Add"),
  @JsonSubTypes.Type(value = AiTasteProfileDelta.Remove.class, name = "Remove"),
  @JsonSubTypes.Type(value = AiTasteProfileDelta.Update.class, name = "Update"),
  @JsonSubTypes.Type(value = AiTasteProfileDelta.Archive.class, name = "Archive"),
  @JsonSubTypes.Type(value = AiTasteProfileDelta.RePromote.class, name = "RePromote"),
  @JsonSubTypes.Type(
      value = AiTasteProfileDelta.PromoteExperiment.class,
      name = "PromoteExperiment"),
  @JsonSubTypes.Type(
      value = AiTasteProfileDelta.DiscardExperiment.class,
      name = "DiscardExperiment"),
  @JsonSubTypes.Type(value = AiTasteProfileDelta.UpdateNotes.class, name = "UpdateNotes"),
})
public sealed interface AiTasteProfileDelta {

  /** Dot-path inside the profile the op targets. Always non-null. */
  String fieldPath();

  /** Which feedback event drove this delta (carried for audit; not part of the apply payload). */
  String evidenceFeedbackId();

  /** One-line justification recorded with the delta in version history. */
  String reasoning();

  /** Confidence on an {@code Add} op (HIGH/MEDIUM/LOW). Only {@code Add} carries it. */
  enum Confidence {
    HIGH,
    MEDIUM,
    LOW
  }

  /** Add a new item to a likes/dislikes list. */
  record Add(
      String fieldPath,
      String item,
      String notes,
      String evidenceFeedbackId,
      String reasoning,
      Confidence confidence)
      implements AiTasteProfileDelta {}

  /** Remove an item from a list (use sparingly — prefer {@link Archive}). */
  record Remove(String fieldPath, String item, String evidenceFeedbackId, String reasoning)
      implements AiTasteProfileDelta {}

  /** Modify the notes on an existing item without changing the item itself. */
  record Update(
      String fieldPath, String item, String newNotes, String evidenceFeedbackId, String reasoning)
      implements AiTasteProfileDelta {}

  /** Move an item from a list into the archive. */
  record Archive(
      String fieldPath,
      String item,
      String archiveReason,
      String evidenceFeedbackId,
      String reasoning)
      implements AiTasteProfileDelta {}

  /** Bring an archived item back into a list. */
  record RePromote(
      String archivedItemKey, String fieldPath, String evidenceFeedbackId, String reasoning)
      implements AiTasteProfileDelta {}

  /** Convert a hypothesis from experiments into a confirmed like/dislike. */
  record PromoteExperiment(
      String hypothesisId,
      String fieldPath,
      String item,
      String evidenceFeedbackId,
      String reasoning)
      implements AiTasteProfileDelta {}

  /** Remove a hypothesis as disproven. The target section is always {@code activeExperiments}. */
  record DiscardExperiment(String hypothesisId, String evidenceFeedbackId, String reasoning)
      implements AiTasteProfileDelta {
    @Override
    public String fieldPath() {
      return "activeExperiments";
    }
  }

  /** Modify free-text notes on a section of the profile. Max one per response. */
  record UpdateNotes(String fieldPath, String newNotes, String evidenceFeedbackId, String reasoning)
      implements AiTasteProfileDelta {}
}
