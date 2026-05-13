package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Peer-module entry for emitting a planner hint. Used by the planner when it notices a cross-recipe
 * interaction (e.g. an absorption conflict between two slots) that the adaptation pipeline should
 * remember on the version row.
 *
 * <p>{@code description} is bounded at 500 chars to keep planner-hint lists scannable in admin
 * dashboards; longer reasoning belongs in {@code payload} or in an adaptation trace.
 *
 * <p>Per LLD §DTOs lines 337-342; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record PlannerHintRequest(
    @NotNull UUID recipeId,
    @NotNull UUID versionId,
    @NotNull UUID branchId,
    @NotNull HintType hintType,
    @NotBlank @Size(max = 500) String description,
    @NotNull JsonNode payload,
    @NotNull HintSeverity severity,
    @Nullable UUID emittedByJobId,
    @NotNull UUID traceId) {}
