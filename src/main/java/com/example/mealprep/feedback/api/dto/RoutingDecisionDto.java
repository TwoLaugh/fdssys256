package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.spi.Destination;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public-facing view of one routing-log row. The {@code id} is the routing-log row id — used to
 * address corrections on the dedicated correction endpoint.
 *
 * <p>{@code destinationResult} is intentionally {@code Object} — Jackson serialises whatever the
 * destination dispatcher placed into the JSONB column. In 01a it surfaces as the raw {@code
 * JsonNode}; the typed-shell logic lands in feedback-01d alongside the destination dispatchers.
 */
public record RoutingDecisionDto(
    UUID id,
    Destination destination,
    BigDecimal confidence,
    RoutingDecision decision,
    RoutingStatus status,
    String extractedFeedback,
    String actionTaken,
    Object destinationResult,
    String failureMessage) {}
