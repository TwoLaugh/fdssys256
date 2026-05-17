package com.example.mealprep.feedback.spi;

/**
 * Cross-module SPI: best-effort undo of a NUTRITION routing when the user corrects a
 * misclassification (lld/feedback.md §Flow 4 step 3, ticket 01f §21).
 *
 * <p>Feedback owns the SPI; the nutrition module supplies a {@code @Component} adapter when it
 * lands its real {@code revertFeedback} surface. Until then a Noop default ({@code
 * NoopFeedbackRevertersConfiguration}) logs WARN and the correction proceeds log-only — the
 * reverter must never throw to block the correction flow.
 */
public interface NutritionFeedbackReverter {

  void revert(RevertContext ctx);
}
