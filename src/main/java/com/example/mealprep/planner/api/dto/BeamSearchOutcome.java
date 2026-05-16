package com.example.mealprep.planner.api.dto;

import java.util.List;

/**
 * Return shape from {@code BeamSearchEngine.search(...)}. Wraps the top-N {@link CandidatePlan}
 * list with a {@code degradedToGreedy} flag the composer (01j) reads to set {@code
 * Plan.qualityWarning = true} per LLD §Failure Modes (second-level timeout fallback).
 *
 * <p>LLD divergence: LLD §{@code BeamSearchEngine} declares {@code List<CandidatePlan>
 * search(...)}; 01d wraps in {@link BeamSearchOutcome} so degradation is explicit at the type level
 * rather than via thread-local / MDC state.
 */
public record BeamSearchOutcome(List<CandidatePlan> candidates, boolean degradedToGreedy) {}
