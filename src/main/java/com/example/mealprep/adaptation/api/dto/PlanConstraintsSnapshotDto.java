package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Plan-time constraint snapshot — pinned by the planner at Stage D entry so the adaptation pipeline
 * sees the same world the planner saw. Carries pantry, budget, equipment, and nutrition targets at
 * the moment the planner asked.
 *
 * <p><b>LLD divergence</b>: LLD line 347 declares the field on {@link
 * PlanTimeRefineDirectiveRequest} but does not spec the shape (the snapshot is owned by the
 * planner). 01b ships a minimal typed wrapper here so {@link PlanTimeRefineDirectiveRequest} can
 * compile; the planner sibling ticket (planner-01h / 01j) may refine the shape. Until then, the
 * adaptation pipeline treats {@code pantrySnapshot} as opaque {@link JsonNode}. <b>Worth user
 * review.</b>
 *
 * @param pantrySnapshot opaque snapshot of user's pantry at plan time — shape pinned by planner
 * @param weeklyBudgetGbp remaining budget for the plan week in GBP
 * @param equipmentAvailable equipment keys available for the plan week
 * @param nutritionTargets nutrient-key → target value (per-day or per-week is planner's call)
 * @param pinnedAt timestamp the planner pinned this snapshot
 */
public record PlanConstraintsSnapshotDto(
    JsonNode pantrySnapshot,
    BigDecimal weeklyBudgetGbp,
    Set<String> equipmentAvailable,
    Map<String, BigDecimal> nutritionTargets,
    Instant pinnedAt) {}
