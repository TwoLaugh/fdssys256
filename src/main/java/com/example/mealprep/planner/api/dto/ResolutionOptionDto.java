package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;

/**
 * A ranked resolution the user can apply to recover from a constraint conflict (planner-6, LLD
 * §Constraint feasibility DTOs). {@code key} is a stable machine key (e.g. {@code "split_slot"},
 * {@code "drop_protein_floor"}, {@code "raise_budget"}, {@code "widen_preferences"}); {@code
 * slotsRecovered} estimates how many under-pooled slots the resolution would un-block; {@code
 * scoreRecovered} is the estimated composite-score gain per unit of constraint loosened (the
 * ranking key — resolutions are returned best-first).
 */
public record ResolutionOptionDto(
    String key, String description, int slotsRecovered, BigDecimal scoreRecovered) {}
