package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an adaptation job transitions to {@code FAILED}. The
 * notification module filters {@code reason == AI_UNAVAILABLE} for the block-and-prompt surface per
 * LLD line 688.
 *
 * <p>Per LLD §Events lines 664-665.
 */
public record AdaptationJobFailedEvent(
    UUID jobId,
    UUID recipeId,
    JobFailureReason reason,
    String excerpt,
    UUID traceId,
    Instant occurredAt)
    implements AdaptationEvent {

  @Override
  public String scopeKind() {
    return "recipe";
  }

  @Override
  public UUID scopeId() {
    return recipeId;
  }
}
