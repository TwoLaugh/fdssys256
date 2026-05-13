package com.example.mealprep.adaptation.api.dto;

import java.util.List;

/**
 * Bulk reply from {@link
 * com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService#lookupForRecipe} —
 * assembles all four kinds of knowledge for the LLM context in one call.
 *
 * <p>Per LLD §Service Interfaces lines 566-570; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record NutritionalKnowledgeBundleDto(
    List<NutritionalPairingDto> pairings,
    List<MethodBioavailabilityDto> methodEffects,
    List<PrepRequirementDto> prepRequirements,
    List<AbsorptionConflictDto> conflicts) {}
