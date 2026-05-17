package com.example.mealprep.feedback.spi;

/**
 * Cross-module SPI: best-effort undo of a RECIPE routing when the user corrects a
 * misclassification. For the recipe destination the "revert" is the optimiser's cancel-pending-
 * adaptation (lld/feedback.md §Flow 4 step 3 / line 797, ticket 01f §23).
 *
 * <p>Feedback owns the SPI; the adaptation-pipeline module supplies a {@code @Component} adapter
 * that maps the original routing id to its pending adaptation and cancels it. Until then a Noop
 * default ({@code NoopFeedbackRevertersConfiguration}) logs WARN and the correction proceeds
 * log-only — the reverter must never throw to block the correction flow.
 */
public interface RecipeFeedbackReverter {

  void revert(RevertContext ctx);
}
