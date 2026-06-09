package com.example.mealprep.nutrition.domain.service.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * AI dispatcher response shape for {@link IngredientMatchTask} — the re-ranked best candidate among
 * the USDA / OFF search hits (LLD nutrition Flow 6 step 5, line 982). {@code chosenIndex} is a
 * 0-based index into the candidate list the task was built with; {@code -1} means "no good match"
 * (the model declined rather than force a wrong pick).
 *
 * <p>The model selects, it never invents: it can only return an index from the supplied list, and
 * emits no nutrition values (those come from the chosen entry downstream).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} so an extra key from the model still
 * deserialises (schema is sent {@code strict:false}; see {@code OpenAiChatClient}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngredientMatchResult(int chosenIndex, BigDecimal confidence, String reason) {

  /** {@code true} when the model declined to match any candidate (LLD "no good match"). */
  public boolean isNoMatch() {
    return chosenIndex < 0;
  }

  /** Confidence as a primitive double, defaulting to 0 when the model omitted it. */
  public double confidenceOrZero() {
    return confidence == null ? 0.0 : confidence.doubleValue();
  }
}
