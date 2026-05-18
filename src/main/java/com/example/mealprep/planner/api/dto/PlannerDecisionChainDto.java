package com.example.mealprep.planner.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * The decision-log chain for one plan (planner-01l admin endpoint). {@code rows} is ordered by
 * {@code createdAt} ascending and forms a single connected DAG via {@link
 * PlannerDecisionRowDto#parentDecisionId()}.
 *
 * <p>Plans generated BEFORE planner-01l shipped have no decision-log rows — {@code rows} is then an
 * empty list (no retroactive backfill; ticket invariant #12).
 *
 * @param planId the plan the chain concerns
 * @param rows the decision rows, earliest-first; never {@code null} (empty for legacy plans)
 */
public record PlannerDecisionChainDto(UUID planId, List<PlannerDecisionRowDto> rows) {}
