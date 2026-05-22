package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.OriginAwareEvent;
import com.example.mealprep.core.origin.Origin;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on every rating POST and PUT (recipe-02b §19-20, C-IMP-009).
 *
 * <p>This is the "closes-the-loop" deliverable: it carries the structured multi-dimensional rating
 * so the feedback module can classify it as feedback (e.g. low {@code effortWorthIt} with high
 * {@code taste} -&gt; "method too involved") and feed the taste-profile learner. The feedback-side
 * listener that consumes this is a follow-up ticket; this ticket ships the publication.
 *
 * <p>Origin is always {@link Origin#USER} — a rating is a direct user action; {@code originTrace}
 * is null per the USER-origin convention.
 */
public record RecipeRatingFiredEvent(
    UUID ratingId,
    UUID userId,
    UUID recipeId,
    UUID versionId,
    UUID slotId,
    int taste,
    Integer effortWorthIt,
    Integer portionFit,
    Integer repeatValue,
    int aggregate,
    String notes,
    UUID traceId,
    Instant occurredAt)
    implements OriginAwareEvent {

  @Override
  public Origin origin() {
    return Origin.USER;
  }

  @Override
  public String originTrace() {
    return null;
  }
}
