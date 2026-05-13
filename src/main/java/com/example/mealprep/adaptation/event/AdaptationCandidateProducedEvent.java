package com.example.mealprep.adaptation.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} after Stage A/B candidate generation, before Stage C. Useful
 * signal for admin dashboards to separate candidate volume from outcome — surfaces "lots of
 * candidates, none chosen" prompt regressions.
 *
 * <p>Per LLD §Events lines 657-658.
 */
public record AdaptationCandidateProducedEvent(
    UUID jobId,
    UUID recipeId,
    int candidateCount,
    BigDecimal topCandidateScore,
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
