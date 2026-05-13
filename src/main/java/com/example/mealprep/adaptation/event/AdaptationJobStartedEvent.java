package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an adaptation job transitions {@code PENDING -> RUNNING}.
 * Listeners use it to mark dashboards and start timers.
 *
 * <p>Per LLD §Events lines 654-655.
 */
public record AdaptationJobStartedEvent(
    UUID jobId,
    UUID recipeId,
    UUID userId,
    JobSource source,
    JobPriority priority,
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
