package com.example.mealprep.nutrition.domain.service.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * AI dispatcher response shape for {@link IngredientParseTask} — the structured parse of one
 * ingredient line (LLD nutrition Flow 6 step 3, line 980). The pipeline consumes {@code
 * usdaSearchTerm} as the term it hands to the USDA / OFF search; the remaining fields are the
 * designed structured parse, carried for callers (e.g. the future snack-log path) that can use a
 * gram estimate or cooked flag.
 *
 * <p><b>No nutrition values</b> — the prompt forbids the model from inventing calories / macros;
 * those are read deterministically from the matched database entry downstream.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} so a model that emits extra keys still
 * deserialises (the schema is sent {@code strict:false}; see {@code OpenAiChatClient}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngredientParseResult(
    String ingredient,
    String usdaSearchTerm,
    BigDecimal quantity,
    String unit,
    BigDecimal gramsEstimate,
    Boolean isCooked,
    BigDecimal confidence) {

  /**
   * The search term to hand to USDA / OFF: the model's {@code usdaSearchTerm} when usable, else
   * {@code null} so the caller falls back to its own normalised term. Never returns a blank.
   */
  public String searchTermOrNull() {
    if (usdaSearchTerm == null || usdaSearchTerm.isBlank()) {
      return null;
    }
    return usdaSearchTerm.trim();
  }
}
