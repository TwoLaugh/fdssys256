package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.spi.ModelTier;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pure converter from {@code (model_id, request_tokens, response_tokens)} to {@code
 * cost_micro_pence}. Anthropic publishes prices in USD per million tokens; we convert to pence per
 * token and store the cost as a {@code long} micropence value (six-decimal pence precision in
 * integer arithmetic, matching {@code lld/ai.md} §Flow 4).
 *
 * <p>Rates here are deliberately encoded as constants — the LLD's per-tier pricing changes with the
 * model lineup, and the code-only update keeps the audit clean. Unknown model ids fall back to the
 * tier the model id starts with (haiku/sonnet/opus); fully unrecognised ids fall back to {@link
 * ModelTier#CHEAP}, the conservative default for budget arithmetic. Returning zero on bad input
 * would mask a misconfiguration as a free call — louder failure is preferable, but we still need to
 * not throw mid-dispatch.
 */
@Component
public class CostCalculator {

  /**
   * Pence per million input tokens. Sourced from Anthropic's published rates (USD → GBP at the
   * project's pinned conversion of 1 USD ≈ 0.79 GBP, then × 100 to pence). These are coarse but
   * stable across the cap-arithmetic horizon — fine-grained accuracy lives in the actual call's
   * logged cost.
   */
  private static final Map<ModelTier, TierRates> RATES = buildRates();

  private static Map<ModelTier, TierRates> buildRates() {
    Map<ModelTier, TierRates> map = new EnumMap<>(ModelTier.class);
    // Haiku 4.5 — $1 / MTok input, $5 / MTok output → ~79p / MTok input, ~395p / MTok output.
    map.put(ModelTier.CHEAP, new TierRates(79L, 395L));
    // Sonnet 4.6 — $3 / MTok input, $15 / MTok output → ~237p / MTok input, ~1185p / MTok output.
    map.put(ModelTier.MID, new TierRates(237L, 1185L));
    // Opus 4.7 — $15 / MTok input, $75 / MTok output → ~1185p / MTok input, ~5925p / MTok output.
    map.put(ModelTier.HIGH, new TierRates(1185L, 5925L));
    return map;
  }

  /**
   * Compute cost in micropence from raw token counts and the resolved model id.
   *
   * <p>Formula: {@code (inputTokens * inputPencePerMTok + outputTokens * outputPencePerMTok)} where
   * the pence-per-million-tokens rates above multiply out to micropence after multiplying by the
   * raw token count (× 1 micropence per pence-per-MTok per token, exact integer arithmetic).
   *
   * @param modelId the {@code model_id} field on the {@code AiCallLog}; nullable rates fall back to
   *     {@link ModelTier#CHEAP}.
   * @param requestTokens prompt-side token count (zero or negative treated as zero).
   * @param responseTokens completion-side token count (zero or negative treated as zero).
   * @return non-negative micropence; never throws.
   */
  public long compute(String modelId, int requestTokens, int responseTokens) {
    TierRates rates = ratesFor(modelId);
    long input = Math.max(0, requestTokens);
    long output = Math.max(0, responseTokens);
    // Each tier rate is pence per million tokens; multiplying by tokens yields micropence.
    long inputCost = input * rates.inputPencePerMillionTokens();
    long outputCost = output * rates.outputPencePerMillionTokens();
    return inputCost + outputCost;
  }

  /** Coarse upper-bound estimate for the budget pre-check. */
  public long estimate(ModelTier tier, int estimatedRequestTokens, int estimatedResponseTokens) {
    TierRates rates = RATES.getOrDefault(tier, RATES.get(ModelTier.CHEAP));
    long input = Math.max(0, estimatedRequestTokens);
    long output = Math.max(0, estimatedResponseTokens);
    return input * rates.inputPencePerMillionTokens()
        + output * rates.outputPencePerMillionTokens();
  }

  private TierRates ratesFor(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return RATES.get(ModelTier.CHEAP);
    }
    String id = modelId.toLowerCase();
    if (id.contains("haiku")) {
      return RATES.get(ModelTier.CHEAP);
    }
    if (id.contains("sonnet")) {
      return RATES.get(ModelTier.MID);
    }
    if (id.contains("opus")) {
      return RATES.get(ModelTier.HIGH);
    }
    return RATES.get(ModelTier.CHEAP);
  }

  /** Pence per million tokens (input / output). */
  record TierRates(long inputPencePerMillionTokens, long outputPencePerMillionTokens) {}
}
