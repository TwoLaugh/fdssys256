package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
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

  /**
   * Route a single synthetic {@link ConfidenceGate.ScoredClassification} for the correction-replay
   * flow (ticket 01f §11, lld/feedback.md §Flow 4 step 6). Reuses the same {@code REQUIRES_NEW}
   * per-destination dispatch as {@link #routeAll} but returns the new routing-log id + dispatch
   * outcome so {@code correctMisclassification} can stamp the {@code MisclassificationCorrection}
   * row. Unlike {@link #routeAll} it does NOT recompute the entry's submission status or publish
   * {@code FeedbackProcessedEvent} — the correction flow owns those.
   *
   * <p>Soft, additive mutation to 01d's interface (no signature changes to {@link #routeAll}).
   */
  RouteReplayResult routeOneForReplay(UUID feedbackId, ConfidenceGate.ScoredClassification scored);

  /** Outcome of a single correction replay dispatch (ticket 01f §11). */
  record RouteReplayResult(
      UUID newRoutingLogId, RoutingStatus status, RoutingFailureKind failureKind) {}
}
