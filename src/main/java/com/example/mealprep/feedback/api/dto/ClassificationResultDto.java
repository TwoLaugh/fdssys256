package com.example.mealprep.feedback.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Public-facing read-side view of one classification attempt. Per lld/feedback.md §DTOs lines
 * 357-363. Declared in 01c because it is part of the LLD's API surface; not exposed by any endpoint
 * yet — feedback-01d/01e/01g may surface it.
 */
public record ClassificationResultDto(
    int attempt,
    Instant performedAt,
    List<RoutingDecisionDto> classifications,
    BigDecimal overallConfidence,
    String classifierNotes) {}
