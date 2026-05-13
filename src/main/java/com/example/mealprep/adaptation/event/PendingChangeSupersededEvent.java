package com.example.mealprep.adaptation.event;

import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a fresher pending change supersedes an older one on the same
 * {@code (recipe_id, change_dimension)} key. Atomicity is enforced by the partial unique index per
 * LLD §Schema; this event lets listeners react after the swap commits.
 *
 * <p>Per LLD §Events lines 671-672.
 */
public record PendingChangeSupersededEvent(
    UUID supersededId,
    UUID supersedingId,
    UUID recipeId,
    ChangeDimension dimension,
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
