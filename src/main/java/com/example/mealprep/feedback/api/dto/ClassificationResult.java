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
 *
 * <p>{@code overallConfidence} is <b>optional</b> (no {@code @NotNull}) — per LLD §DTOs line 276 it
 * is "an aggregate the classifier MAY emit". Since the classification prompt template is deferred
 * (it cannot be relied on to always emit the field), the contract treats a missing value as
 * acceptable; the per-route {@code confidence} on each {@link ClassificationOutput} is the
 * authoritative score for the ConfidenceGate. When present it is range-validated [0,1].
 */
public record ClassificationResult(
    @NotNull @Size(min = 0, max = 4) List<@Valid ClassificationOutput> classifications,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal overallConfidence,
    String classifierNotes) {}
