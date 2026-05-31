package com.example.mealprep.planner.api.dto;

import java.util.List;

/**
 * Result of the pre-Stage-A constraint feasibility check (planner-6, LLD
 * §ConstraintFeasibilityCheck / §Constraint feasibility DTOs). The UI calls {@code GET
 * /api/v1/plans/feasibility} before triggering generation so a resolution dialog can render even
 * before Stage A starts.
 *
 * <p>{@code feasible == true} means every slot's post-hard-filter pool meets the configured minimum
 * ({@code mealprep.planner.min-pool-per-slot}); {@code conflicts} and {@code resolutions} are then
 * empty. When {@code feasible == false}, {@code conflicts} classifies each under-pooled slot group
 * and {@code resolutions} ranks the relaxations that would recover the most slots/score first.
 */
public record FeasibilityCheckResultDto(
    boolean feasible,
    List<ConstraintConflictDto> conflicts,
    List<ResolutionOptionDto> resolutions) {}
