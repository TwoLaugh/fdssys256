package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Trigger-3 request — batch entry for user data-model changes (preference / nutrition-targets /
 * provisions-budget / hard-constraints). Async; the service returns the list of enqueued job ids,
 * one per affected recipe.
 *
 * <p>{@code affectedRecipeIds} is capped at 5000 per LLD line 334 — beyond that, callers are
 * expected to chunk. Validation failure returns 400 (request-level).
 *
 * <p>Per LLD §DTOs lines 329-335; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record DataModelJobRequest(
    @NotNull UUID userId,
    @NotNull DataModelChangeType changeType,
    @NotNull JsonNode changeSummary,
    @NotNull @Size(max = 5000) Set<UUID> affectedRecipeIds,
    @NotNull UUID traceId) {}
