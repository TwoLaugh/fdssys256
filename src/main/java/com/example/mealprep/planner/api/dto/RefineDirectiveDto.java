package com.example.mealprep.planner.api.dto;

import java.util.UUID;

/**
 * <b>Planner-local placeholder</b> for the cross-module refine-directive contract forwarded to the
 * adaptation pipeline's Stage D. Per ticket planner-01h §"LLD divergence — {@code
 * RefineDirectiveDto} shape" and {@code tickets/WAVE3-NAMING-RECONCILIATION.md}.
 *
 * <p><b>Why a placeholder and not the real adaptation type?</b> The ticket/LLD predicted a
 * top-level {@code com.example.mealprep.adaptation.api.dto.RefineDirectiveDto}. The adaptation
 * module that merged through 01b/01e instead defines {@code RefineDirectiveDto} as a <i>nested</i>
 * record inside {@code PlanTimeRefineDirectiveRequest} ({@code RefineDirectiveDto(DirectiveKind
 * kind, String description, JsonNode targetDelta)}) — a different shape, reached via {@code
 * AdaptationService.runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)}, not a direct {@code
 * OptimiserService.adapt(RefineDirectiveDto)} call. The two shapes are incompatible and the
 * directive→request assembly (recipeId, planId, slotId, constraints snapshot, parentDecisionId) is
 * the composer's job in planner-01j, not Phase 2's.
 *
 * <p>Per the ticket's documented deferral, 01h ships this placeholder so {@code AugmentationResult}
 * has a stable, planner-owned {@code emittedDirectives} element type, and {@code
 * Phase2AugmenterImpl} emits an <b>empty</b> {@code emittedDirectives} list with an INFO log until
 * the composer (01j) wires the real adaptation request. The composer reconciles the conversion when
 * it assembles {@code PlanTimeRefineDirectiveRequest}.
 *
 * <p>TODO(planner-01j / wave-3 reconciliation): replace usages of this placeholder with the
 * assembly of {@code com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest} (which
 * carries the nested {@code RefineDirectiveDto}).
 */
public record RefineDirectiveDto(
    String kind, UUID targetSlotId, String description, String fromKey, String toKey) {}
