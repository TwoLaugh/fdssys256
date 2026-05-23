package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Deterministic token-budget guard for the AI-maintained taste profile (preference-01f).
 *
 * <p>The HLD caps the document at ~2500 tokens ({@code design/preference-model.md:69}) so it stays
 * small enough to inline into every planner / optimiser / discovery prompt. After a delta batch is
 * applied, {@link #estimate(TasteProfileDocument)} produces a deterministic token estimate; if it
 * exceeds {@link #MAX_TOKENS} the whole batch is rejected via {@link
 * TasteProfileBudgetExceededException} (422) and the surrounding transaction rolls back — no
 * partial state.
 *
 * <p>The estimate is a serialise-and-divide heuristic: the document is rendered to canonical JSON
 * via the injected {@link ObjectMapper} and the character count is divided by {@link
 * #CHARS_PER_TOKEN}. The only contract the LLD requires is <i>determinism</i> ({@code
 * lld/preference.md:770}) — identical documents always yield identical counts, and adding content
 * monotonically raises the estimate. The serialisation is the same {@code ObjectMapper} Spring uses
 * for the JSONB column, so the estimate tracks the on-the-wire prompt size closely.
 */
@Component
public class TasteProfileBudgetGuard {

  /** Per {@code design/preference-model.md:69} — the ~2500-token compression budget. */
  public static final int MAX_TOKENS = 2500;

  /**
   * Characters-per-token divisor. ~4 is the well-known rule of thumb for English + JSON punctuation
   * across GPT/Claude-family BPE tokenisers. Exact tokeniser fidelity is not required (the budget
   * is a guardrail, not a billing meter); determinism is.
   */
  static final int CHARS_PER_TOKEN = 4;

  private final ObjectMapper objectMapper;

  public TasteProfileBudgetGuard(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Deterministic token estimate for {@code doc}: {@code ceil(jsonCharCount / CHARS_PER_TOKEN)}.
   * Never negative; an empty document still serialises to a small non-zero JSON object.
   */
  public int estimate(TasteProfileDocument doc) {
    int chars = serialise(doc).length();
    // Ceiling division so any non-empty payload costs at least one token and growth is monotonic.
    return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
  }

  /**
   * Runs {@link #estimate(TasteProfileDocument)} on the post-apply {@code doc} and throws if it
   * exceeds {@link #MAX_TOKENS}. Returns the computed estimate when within budget so the caller can
   * stamp {@code lastTokenEstimate}.
   */
  public int enforce(TasteProfileDocument doc) {
    int estimate = estimate(doc);
    if (estimate > MAX_TOKENS) {
      throw new TasteProfileBudgetExceededException(
          "taste profile exceeds token budget: estimate="
              + estimate
              + " > max="
              + MAX_TOKENS
              + " (the AI should have proposed archives first)");
    }
    return estimate;
  }

  private String serialise(TasteProfileDocument doc) {
    try {
      return objectMapper.writeValueAsString(doc);
    } catch (JsonProcessingException e) {
      // The document is a plain record tree mapped by the same ObjectMapper the JSONB column uses;
      // it round-trips on every write. A failure here is a genuine configuration bug, not a
      // user-input problem — surface it rather than silently under-counting the budget.
      throw new IllegalStateException("failed to serialise taste profile document for budget", e);
    }
  }
}
