package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a new {@code PENDING} change row is written. The notification
 * module subscribes to surface "proposal ready" prompts; the planner subscribes to invalidate stale
 * slot recommendations.
 *
 * <p>Per LLD §Events lines 667-669.
 */
public record PendingChangeCreatedEvent(
    UUID pendingChangeId,
    UUID recipeId,
    UUID userId,
    ChangeDimension dimension,
    BigDecimal confidence,
    BigDecimal impactScore,
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
