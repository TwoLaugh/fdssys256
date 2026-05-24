package com.example.mealprep.provisions.spi.internal;

import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real {@link ProvisionsFeedbackReverter} (feedback-01h §9-11). As a plain {@code @Component} it
 * out-ranks the feedback module's {@code NoopFeedbackRevertersConfiguration}
 * {@code @Bean @ConditionalOnMissingBean}, so with provisions on the classpath the feedback service
 * resolves this bean. (The Noop stays for feedback-only test slices that don't load provisions.)
 *
 * <p><b>Provisions revert is effectively log-only in v1</b> — by the LLD's immutability rule
 * (lld/feedback.md §Flow 4 step 3 / line 800), the implemented provisions feedback actions are both
 * one-way and have no clean inverse on {@code ProvisionUpdateService}:
 *
 * <ul>
 *   <li><b>{@code REMOVE_EQUIPMENT}</b> — equipment deletion is immutable; re-adding equipment
 *       would fabricate a row the user never created (feedback-01h §10). Log-only.
 *   <li><b>{@code MARK_DEPLETED}</b> — {@code markExhausted} is one-way; there is no "un-exhaust"
 *       surface and inventing one would mis-state real-world stock (feedback-01h §10, out-of-scope
 *       audit). Log-only.
 *   <li>Cost-concern logs / supplier-cache writes (the reserved {@code ADJUST_BUDGET} family) —
 *       unimplemented today; log-only with {@code unsupported-provisions-revert}.
 * </ul>
 *
 * <p>This reverter does <b>not</b> fabricate inverse writes (feedback-01h §11): its value is the
 * structured WARN naming the un-revertable action for quality monitoring. The synthetic replay
 * (fired by the caller) re-routes the feedback to its corrected destination regardless. The
 * reverter never throws — the SPI contract requires it not to block the correction.
 */
@Component
public class ProvisionsFeedbackReverterImpl implements ProvisionsFeedbackReverter {

  private static final Logger log = LoggerFactory.getLogger(ProvisionsFeedbackReverterImpl.class);

  /** Quality-monitoring tag: provisions writes are immutable, no inverse exists (feedback-01h). */
  private static final String UNSUPPORTED_REVERT = "unsupported-provisions-revert";

  @Override
  public void revert(RevertContext ctx) {
    String action = readAction(ctx.structuredPayload());
    log.warn(
        "provisions revert is log-only ({}); action {} is immutable per the LLD — original write on"
            + " routing {} kept, correction proceeds (the corrected destination replay reconciles)",
        UNSUPPORTED_REVERT,
        action == null ? "(unknown)" : action,
        ctx.originalRoutingId());
  }

  /**
   * The {@code provisionsAction} the provisions bridge disambiguated on (e.g. REMOVE_EQUIPMENT).
   */
  private static String readAction(JsonNode structuredPayload) {
    if (structuredPayload == null) {
      return null;
    }
    JsonNode node = structuredPayload.path("provisionsAction");
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
