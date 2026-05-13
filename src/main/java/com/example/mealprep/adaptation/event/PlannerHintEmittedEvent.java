package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a new {@code adaptation_planner_hints} row is written. The
 * planner subscribes to refresh its in-process hint cache for the targeted version.
 *
 * <p>Per LLD §Events lines 680-681.
 */
public record PlannerHintEmittedEvent(
    UUID hintId,
    UUID recipeId,
    UUID versionId,
    HintType type,
    HintSeverity severity,
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
