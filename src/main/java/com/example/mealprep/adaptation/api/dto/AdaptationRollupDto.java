package com.example.mealprep.adaptation.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rollup delta summary attached to each {@link AdaptationCandidateDto}. Carries the diff vs the
 * base version across macros, micros, cost, time, ingredient count, taste alignment, and equipment.
 *
 * <p>{@code microDeltas} keys mirror nutrition module's nutrient keys (e.g. {@code "iron_mg"},
 * {@code "folate_ug"}); {@code equipmentDelta} is a set of equipment keys that the candidate adds
 * over the base; {@code warnings} is a list of free-text strings surfaced for prompt-quality
 * dashboards.
 *
 * <p>Per LLD §DTOs lines 385-389; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record AdaptationRollupDto(
    BigDecimal macroDeltaProteinG,
    BigDecimal macroDeltaCarbsG,
    BigDecimal macroDeltaFatG,
    BigDecimal macroDeltaKcal,
    Map<String, BigDecimal> microDeltas,
    BigDecimal costDeltaGbp,
    Integer timeDeltaMins,
    Integer ingredientCountDelta,
    BigDecimal tasteAlignmentScore,
    Set<String> equipmentDelta,
    List<String> warnings) {}
