package com.example.mealprep.nutrition.spi.internal;

import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real {@link NutritionFeedbackReverter} (feedback-01h §12-13). As a plain {@code @Component} it
 * out-ranks the feedback module's {@code NoopFeedbackRevertersConfiguration}
 * {@code @Bean @ConditionalOnMissingBean}, so with nutrition on the classpath the feedback service
 * resolves this bean. (The Noop stays for feedback-only test slices that don't load nutrition.)
 *
 * <p><b>Nutrition revert is log-only / best-effort in v1.</b> The implemented nutrition feedback
 * path (nutrition/01i — {@code applyFeedbackAdjustment}) is a direct single-field target write
 * (audited {@code actor_kind = feedback}, {@code @Version} bumped), <b>not</b> a propose/accept
 * proposal — there is no un-accepted proposal to cancel (lld/feedback.md §Flow 4 step 3 / line
 * 799). Undoing a feedback target nudge would require a stored before-value plus an inverse write,
 * which is the deferred adjustment-undo ({@code C-IMP-021}, explicitly out of scope per
 * tickets/nutrition/01i §What's NOT in scope). This reverter therefore logs a structured WARN
 * naming the un-reverted target (read from the classifier payload) + the {@code
 * feedback-<feedbackId>} origin trace (from the bridge's result), and does NOT write an inverse.
 * When a future propose/accept nutrition feedback flow (Flow 10 proposals) lands, this reverter
 * cancels the un-accepted proposal then.
 *
 * <p>The reverter never throws — the SPI contract requires it not to block the correction; the
 * synthetic replay (fired by the caller) re-routes regardless.
 */
@Component
public class NutritionFeedbackReverterImpl implements NutritionFeedbackReverter {

  private static final Logger log = LoggerFactory.getLogger(NutritionFeedbackReverterImpl.class);

  @Override
  public void revert(RevertContext ctx) {
    String target = readText(ctx.structuredPayload(), "target");
    String originTrace = readText(ctx.destinationResultJson(), "originTrace");
    log.warn(
        "nutrition revert is log-only; the feedback target nudge (target {}, origin {}) on routing"
            + " {} has no clean inverse (C-IMP-021 deferred) — target left as-is, correction"
            + " proceeds (the corrected destination replay reconciles)",
        target == null ? "(unknown)" : target,
        originTrace == null ? "(unknown)" : originTrace,
        ctx.originalRoutingId());
  }

  private static String readText(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }
}
