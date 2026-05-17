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
 *   <li>{@code emittedDirectives} — refine-directives forwarded to Stage D by the composer
 *       (planner-01j). Always empty in 01h: the real cross-module contract is the adaptation
 *       module's {@code PlanTimeRefineDirectiveRequest}, assembled by the composer (see {@link
 *       RefineDirectiveDto} Javadoc).
 * </ul>
 *
 * <p>{@code Augmentation} here is the <b>typed</b> sealed hierarchy, not the raw {@code
 * AugmentationProposal}.
 */
public record AugmentationResult(
    List<Augmentation> applied,
    List<Augmentation> discardedByVerifier,
    List<RefineDirectiveDto> emittedDirectives) {}
