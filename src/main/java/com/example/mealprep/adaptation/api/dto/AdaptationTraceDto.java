package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only adaptation-trace projection. One row per completed job — captures the full A → B → C
 * → Apply path so admin / debug surfaces can replay a decision.
 *
 * <p>{@code rawAiResponse} is nullable — null when Stage C is auto-skipped (deterministic top-2x
 * rule); {@code chosenCandidateIndex}, {@code classificationDecision}, {@code finalDiff}, {@code
 * confidence}, {@code characterPreservationScore}, {@code aiCallId}, {@code outcomeTargetId} are
 * all nullable per the LLD's verbatim annotations.
 *
 * <p>Per LLD §DTOs lines 394-401; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record AdaptationTraceDto(
    UUID id,
    UUID jobId,
    UUID recipeId,
    UUID traceId,
    JobSource source,
    String promptTemplateName,
    String promptTemplateVersion,
    @Nullable UUID aiCallId,
    JsonNode inputsSnapshot,
    @Nullable JsonNode rawAiResponse,
    JsonNode candidates,
    @Nullable Integer chosenCandidateIndex,
    @Nullable AdaptationClassification classificationDecision,
    @Nullable JsonNode finalDiff,
    @Nullable BigDecimal confidence,
    @Nullable BigDecimal characterPreservationScore,
    ValidationResult validationResult,
    OutcomeKind outcomeKind,
    @Nullable UUID outcomeTargetId,
    int durationMs,
    Instant createdAt) {}
