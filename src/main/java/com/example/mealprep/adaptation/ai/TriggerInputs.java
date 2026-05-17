package com.example.mealprep.adaptation.ai;

import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.PlanConstraintsSnapshotDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;

/**
 * Typed sum-type carrier for the trigger-specific payload that {@link AdaptationContextAssembler}
 * folds into an {@link AdaptationContext}. One variant per {@code JobSource}; the assembler reads
 * the four nullable accessor defaults so it never branches on {@code instanceof} for the common "is
 * there feedback / a directive" case.
 *
 * <p>Per ticket 01e step 8; the trigger-payload shapes mirror {@code
 * lld/prompts/05-recipe-adaptation.md} lines 70-93. Variants are records so they round-trip cleanly
 * through the project's Jackson {@code ObjectMapper}.
 */
public sealed interface TriggerInputs
    permits TriggerInputs.ImportTriggerInputs,
        TriggerInputs.FeedbackTriggerInputs,
        TriggerInputs.DataModelTriggerInputs,
        TriggerInputs.PlanTimeTriggerInputs {

  /** Verbatim user feedback — non-null only for {@link FeedbackTriggerInputs}. */
  @Nullable
  default String feedbackText() {
    return null;
  }

  /** Structured rating delta — non-null only for {@link FeedbackTriggerInputs}. */
  @Nullable
  default FeedbackJobRequest.RatingDeltaDto ratingDelta() {
    return null;
  }

  /** Plan-time refine directive — non-null only for {@link PlanTimeTriggerInputs}. */
  @Nullable
  default PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive() {
    return null;
  }

  /** Data-model-change summary — non-null only for {@link DataModelTriggerInputs}. */
  @Nullable
  default JsonNode dataModelChange() {
    return null;
  }

  /** IMPORT: the raw scraped/import context (may be {@code null} for a manual import). */
  record ImportTriggerInputs(@Nullable JsonNode rawImportContext) implements TriggerInputs {}

  /** FEEDBACK: verbatim text + structured rating delta. */
  record FeedbackTriggerInputs(String feedbackText, FeedbackJobRequest.RatingDeltaDto ratingDelta)
      implements TriggerInputs {}

  /** DATA_MODEL_CHANGE: which surface mutated + a JSON summary of the change. */
  record DataModelTriggerInputs(DataModelChangeType changeType, JsonNode changeSummary)
      implements TriggerInputs {
    @Override
    public JsonNode dataModelChange() {
      return changeSummary;
    }
  }

  /** PLAN_TIME_REFINE: the directive + a snapshot of the plan constraints in effect. */
  record PlanTimeTriggerInputs(
      PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive,
      PlanConstraintsSnapshotDto constraints)
      implements TriggerInputs {}
}
