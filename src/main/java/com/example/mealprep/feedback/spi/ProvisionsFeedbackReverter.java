package com.example.mealprep.feedback.spi;

/**
 * Cross-module SPI: best-effort undo of a PROVISIONS routing when the user corrects a
 * misclassification (lld/feedback.md §Flow 4 step 3, ticket 01f §22).
 *
 * <p>Feedback owns the SPI; the provisions module supplies a {@code @Component} adapter when it
 * lands its real {@code revertFeedback} surface. Until then a Noop default ({@code
 * NoopFeedbackRevertersConfiguration}) logs WARN and the correction proceeds log-only — the
 * reverter must never throw to block the correction flow.
 */
public interface ProvisionsFeedbackReverter {

  void revert(RevertContext ctx);
}
