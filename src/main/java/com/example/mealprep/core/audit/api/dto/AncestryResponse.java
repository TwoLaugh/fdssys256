package com.example.mealprep.core.audit.api.dto;

import java.util.List;

/**
 * Response shape for {@link
 * com.example.mealprep.core.audit.domain.service.DecisionLogQueryService#getAncestry}. Ancestors
 * are root-first; the input {@code decisionId} itself is excluded.
 *
 * <p>{@code cycleDetected} is set true when the recursive walk hit the configured depth cap
 * (default 32) — likely indicating a malformed cycle in {@code parentDecisionId}. The returned
 * ancestors are still useful as a partial answer.
 */
public record AncestryResponse(List<DecisionLogDto> ancestors, boolean cycleDetected) {}
