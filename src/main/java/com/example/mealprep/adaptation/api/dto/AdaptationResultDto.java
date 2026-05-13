package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sync return type of {@link
 * com.example.mealprep.adaptation.domain.service.AdaptationService#runPlanTimeRefineJob} and {@link
 * com.example.mealprep.adaptation.domain.service.AdaptationService#enqueueFeedbackJob}; also
 * attached to {@code AdaptationJobCompletedEvent} for async triggers.
 *
 * <p>The four "Optional&lt;UUID&gt; ...Created" fields make the classification outcomes legible
 * without sibling result types — exactly one is populated, mirroring {@link
 * AdaptationClassification}'s {@code VERSION | BRANCH | SUBSTITUTION | NO_CHANGE} variants. {@code
 * NO_CHANGE} = infeasibility OR adaptation rejected by gates; sibling callers treat both the same.
 *
 * <p>Per LLD §DTOs lines 300-306; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record AdaptationResultDto(
    UUID jobId,
    UUID recipeId,
    AdaptationClassification classification,
    Optional<UUID> versionIdCreated,
    Optional<UUID> branchIdCreated,
    Optional<UUID> substitutionIdCreated,
    Optional<UUID> pendingChangeIdCreated,
    JsonNode proposedDiff,
    String reasoning,
    String nutritionalNotes,
    boolean requiresApproval,
    List<PlannerHintDto> plannerHints,
    UUID traceId,
    BigDecimal confidence) {}
