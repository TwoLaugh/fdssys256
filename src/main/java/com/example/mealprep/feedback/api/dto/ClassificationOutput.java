package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One element of {@link ClassificationResult#classifications()}. The classifier emits one of these
 * per destination it wants to route the feedback to. Verbatim from lld/feedback.md §DTOs lines
 * 280-285.
 *
 * <p>{@code structuredPayload} is loose ({@link JsonNode}) because each destination uses different
 * fields; the destination dispatcher in feedback-01d parses it against the destination-specific
 * shape.
 */
public record ClassificationOutput(
    @NotNull Destination destination,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    @NotBlank String extractedFeedback,
    @NotNull JsonNode structuredPayload) {}
