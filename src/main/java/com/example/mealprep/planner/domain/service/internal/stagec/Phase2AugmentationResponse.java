package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.planner.api.dto.AugmentationProposal;
import com.example.mealprep.planner.api.dto.RefineDirectiveProposal;
import java.util.List;

/**
 * The structured-output payload the AI dispatcher Jackson-binds the Phase-2 tool call into. Per
 * lld/planner.md §{@code Phase2AugmentationResponse} (lines 908-910). Both lists carry the LLM's
 * <b>raw</b> shapes; {@code AugmentationParser} types the augmentations and the composer (01j)
 * converts the refine-directives to the adaptation contract.
 */
public record Phase2AugmentationResponse(
    List<AugmentationProposal> augmentations, List<RefineDirectiveProposal> refineDirectives) {

  /** Null-safe accessors so a sparse LLM payload never NPEs the augmenter's stream pipeline. */
  public List<AugmentationProposal> augmentations() {
    return augmentations == null ? List.of() : augmentations;
  }

  public List<RefineDirectiveProposal> refineDirectives() {
    return refineDirectives == null ? List.of() : refineDirectives;
  }
}
