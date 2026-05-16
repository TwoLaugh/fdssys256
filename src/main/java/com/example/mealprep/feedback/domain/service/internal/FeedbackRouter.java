package com.example.mealprep.feedback.domain.service.internal;

import java.util.List;
import java.util.UUID;

/**
 * Internal SPI implemented by feedback-01d's real router. 01c ships a Noop default via the
 * {@code @Configuration + @Bean + @ConditionalOnMissingBean} recipe so the listener can hand off
 * without a hard dependency on 01d.
 *
 * <p>Lives in {@code domain.service.internal} (not {@code spi/}) because this seam is internal to
 * the feedback module — cross-module SPIs go in {@code spi/}.
 */
public interface FeedbackRouter {

  /**
   * Route every {@link ConfidenceGate.ScoredClassification} (decisions {@code AUTO_ROUTED} or
   * {@code ROUTED_WITH_FLAG}) to its destination dispatcher. Implementations open their own {@code
   * REQUIRES_NEW} transaction per destination and publish {@code FeedbackProcessedEvent} once all
   * destinations have been processed.
   */
  void routeAll(UUID feedbackId, List<ConfidenceGate.ScoredClassification> classifications);
}
