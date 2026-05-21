package com.example.mealprep.discovery.domain.service.internal;

import java.math.BigDecimal;

/**
 * AI dispatcher response shape for {@link CandidateFilterTask}. Deserialised by the {@code
 * AiService} from the model's structured-output (Anthropic tool use) for the {@code
 * discovery-candidate-filter.v1} prompt.
 *
 * <p>{@code confidence} is the 0.00-1.00 self-rated confidence; the runner gates against {@code
 * mealprep.discovery.candidate-filter.min-confidence} (default 0.6).
 */
public record CandidateFilterResult(boolean relevant, BigDecimal confidence, String reason) {}
