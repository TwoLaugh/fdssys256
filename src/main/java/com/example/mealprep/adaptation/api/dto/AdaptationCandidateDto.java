package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * One candidate produced by Stage A / B of the pipeline; the chosen candidate's index is recorded
 * on {@link AdaptationTraceDto#chosenCandidateIndex()}.
 *
 * <p>Note: {@code AdaptationCandidate} is a domain record (not persisted in its own row) — see LLD
 * line 408. The corresponding entity-to-record mapping lives inside {@code CandidateGenerator}
 * (01c); only the public DTO ships in 01b.
 *
 * <p>Per LLD §DTOs lines 379-383; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record AdaptationCandidateDto(
    int index,
    AdaptationClassification proposedClassification,
    JsonNode proposedDiff,
    AdaptationRollupDto rollup,
    String culinaryNotes,
    String nutritionalNotes,
    BigDecimal characterPreservationScore,
    BigDecimal estimatedConfidence,
    List<PlannerHintDto> plannerHints) {}
