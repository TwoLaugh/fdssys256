package com.example.mealprep.feedback.domain.entity;

/**
 * Terminal outcome recorded on a {@link FeedbackBridgeIdempotency} row by a destination bridge per
 * tickets/feedback/01g §4. Persisted as {@code varchar(32)}.
 */
public enum BridgeDispatchStatus {

  /** The bridge successfully dispatched the classifier output to the destination service. */
  DISPATCHED,

  /** Confidence was below the 0.5 floor — the bridge skipped the dispatch (no destination call). */
  REJECTED_LOW_CONFIDENCE,

  /**
   * The destination call threw (or the destination surface is not yet feedback-ready, e.g. the
   * stubbed taste-profile applier). The original feedback transaction is unaffected.
   */
  FAILED
}
