package com.example.mealprep.feedback.spi;

/**
 * The four destinations the feedback module routes a classified submission to. Lives in {@code
 * spi/} because the {@code DestinationDispatcher} SPIs in feedback-01d are keyed by {@code
 * Map<Destination, DestinationDispatcher>} — cross-module visibility is required.
 *
 * <p>Order matches the LLD listing — RECIPE first because it is the most-frequent destination.
 */
public enum Destination {
  RECIPE,
  PREFERENCE,
  NUTRITION,
  PROVISIONS
}
