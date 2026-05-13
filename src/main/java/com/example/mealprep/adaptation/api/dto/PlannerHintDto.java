package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Planner-hint projection. Carries the persistent id (so callers can ack / invalidate), the typed
 * hint kind, a free-text description, the structured payload, and a severity.
 *
 * <p>Per LLD §DTOs lines 391-392; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record PlannerHintDto(
    UUID id, HintType type, String description, JsonNode payload, HintSeverity severity) {}
