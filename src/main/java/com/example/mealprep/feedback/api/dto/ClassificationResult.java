package com.example.mealprep.feedback.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Structured output emitted by the feedback classification AI task. Verbatim from lld/feedback.md
 * §DTOs lines 274-278.
 *
 * <p>{@code @Size(min = 0, max = 4)} — empty is allowed (per LLD line 290, a feedback the
 * classifier deems non-actionable routes to nothing); {@code max = 4} matches the four-destination
 * universe of {@link com.example.mealprep.feedback.spi.Destination}.
 */
public record ClassificationResult(
    @NotNull @Size(min = 0, max = 4) List<@Valid ClassificationOutput> classifications,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal overallConfidence,
    String classifierNotes) {}
