package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Snapshot of the planned-vs-actual divergence at the moment an intake update fired the detector.
 * All three maps are keyed by macro name (e.g. {@code "protein"}); {@code percentVariance} uses
 * fractional units (0.20 means +20%).
 */
public record DivergenceSummaryDto(
    Map<String, BigDecimal> plannedSoFar,
    Map<String, BigDecimal> actualSoFar,
    Map<String, BigDecimal> percentVariance) {}
