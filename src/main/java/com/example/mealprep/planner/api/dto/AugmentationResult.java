package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import java.util.List;

/**
 * Carrier returned by {@code Phase2Augmenter.augment}. Per lld/planner.md §{@code Phase2Augmenter}
 * (lines 876-880) and ticket planner-01h §"{@code AugmentationResult}".
 *
 * <ul>
 *   <li>{@code applied} — augmentations that survived {@link
 *       com.example.mealprep.planner.domain.service.internal.stagec.AugmentationVerifier}.
 *   <li>{@code discardedByVerifier} — augmentations dropped silently (logged WARN by the
 *       augmenter), kept here for the decision log (planner-01l).
 *   <li>{@code emittedDirectives} — raw refine-directive proposals (the Phase-2 LLM output shape)
 *       forwarded to Stage D by the composer (planner-01j). The composer maps each {@link
 *       RefineDirectiveProposal} onto the adaptation module's {@code
 *       PlanTimeRefineDirectiveRequest} via {@code RefineDirectiveMapper} and dispatches {@code
 *       AdaptationService.runPlanTimeRefineJob(...)}. Bounded by {@code maxRefineDirectives}. Empty
 *       when the LLM emits no directives (or on an AI-degrade).
 * </ul>
 *
 * <p>Carrying the raw {@link RefineDirectiveProposal} (rather than a lossy planner-local
 * placeholder) keeps every directive field — kind, target slot, from/to ingredient keys,
 * current/target time minutes, reasoning — intact end-to-end until the composer assembles the
 * cross-module request.
 *
 * <p>{@code Augmentation} here is the <b>typed</b> sealed hierarchy, not the raw {@code
 * AugmentationProposal}.
 */
public record AugmentationResult(
    List<Augmentation> applied,
    List<Augmentation> discardedByVerifier,
    List<RefineDirectiveProposal> emittedDirectives) {}
