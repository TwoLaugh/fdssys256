package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.ExperimentStatus;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sealed taxonomy of taste-profile mutations produced by the AI delta task in the feedback module.
 * Eight permits map one-for-one to the operations described in {@code lld/preference.md:358-373}.
 * Jackson polymorphism keys on the {@code op} property — each permit declares its
 * {@code @JsonTypeName}.
 *
 * <p>01c ships the shape only — the consumer {@code TasteProfileDeltaApplier} is a stub that throws
 * when called (see {@code preference.domain.service.internal.TasteProfileDeltaApplier}). The
 * feedback bridge in {@code tickets/feedback/01g} must NOT call into the stub until the deferred
 * {@code 01c-delta-applier} ticket ships the real implementation.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TasteProfileDelta.Add.class, name = "ADD"),
  @JsonSubTypes.Type(value = TasteProfileDelta.Remove.class, name = "REMOVE"),
  @JsonSubTypes.Type(value = TasteProfileDelta.Update.class, name = "UPDATE"),
  @JsonSubTypes.Type(value = TasteProfileDelta.UpdateNotes.class, name = "UPDATE_NOTES"),
  @JsonSubTypes.Type(
      value = TasteProfileDelta.PromoteExperiment.class,
      name = "PROMOTE_EXPERIMENT"),
  @JsonSubTypes.Type(
      value = TasteProfileDelta.DiscardExperiment.class,
      name = "DISCARD_EXPERIMENT"),
  @JsonSubTypes.Type(value = TasteProfileDelta.Archive.class, name = "ARCHIVE"),
  @JsonSubTypes.Type(value = TasteProfileDelta.RePromote.class, name = "RE_PROMOTE"),
})
public sealed interface TasteProfileDelta {

  /** Dot-path inside {@code TasteProfileDocument} that the op targets. Always non-null. */
  String fieldPath();

  record Add(@NotBlank @Size(max = 128) String fieldPath, JsonNode item)
      implements TasteProfileDelta {}

  record Remove(
      @NotBlank @Size(max = 128) String fieldPath, @NotBlank @Size(max = 128) String itemKey)
      implements TasteProfileDelta {}

  record Update(
      @NotBlank @Size(max = 128) String fieldPath,
      @NotBlank @Size(max = 128) String itemKey,
      JsonNode patch)
      implements TasteProfileDelta {}

  record UpdateNotes(@NotBlank @Size(max = 128) String fieldPath, @Size(max = 512) String notes)
      implements TasteProfileDelta {}

  record PromoteExperiment(
      @NotBlank @Size(max = 512) String hypothesis,
      @NotBlank @Size(max = 128) String targetFieldPath,
      JsonNode promotedItem)
      implements TasteProfileDelta {
    @Override
    public String fieldPath() {
      return targetFieldPath;
    }
  }

  record DiscardExperiment(@NotBlank @Size(max = 512) String hypothesis)
      implements TasteProfileDelta {
    @Override
    public String fieldPath() {
      return "activeExperiments";
    }
  }

  record Archive(
      @NotBlank @Size(max = 128) String fieldPath,
      @NotBlank @Size(max = 128) String itemKey,
      @NotBlank @Size(max = 32) String reason)
      implements TasteProfileDelta {}

  record RePromote(
      @NotBlank @Size(max = 128) String fieldPath, @NotBlank @Size(max = 128) String itemKey)
      implements TasteProfileDelta {}

  /**
   * Stable enum mirror of the {@code op} discriminator — used by the (deferred) applier's
   * exhaustive switch.
   */
  enum Kind {
    ADD,
    REMOVE,
    UPDATE,
    UPDATE_NOTES,
    PROMOTE_EXPERIMENT,
    DISCARD_EXPERIMENT,
    ARCHIVE,
    RE_PROMOTE
  }

  /** Promoted item's status after a {@code PromoteExperiment} apply (defaults to PROMOTED). */
  static ExperimentStatus defaultPromotedStatus() {
    return ExperimentStatus.PROMOTED;
  }
}
