package com.example.mealprep.feedback.exception;

import java.util.UUID;

/**
 * Thrown when a routing-log lookup yields no row for the caller. Same 404 collapse as {@link
 * FeedbackEntryNotFoundException}.
 */
public class RoutingDecisionNotFoundException extends FeedbackException {

  private final UUID routingId;

  public RoutingDecisionNotFoundException(UUID routingId) {
    super("Routing decision not found: " + routingId);
    this.routingId = routingId;
  }

  public UUID routingId() {
    return routingId;
  }
}
