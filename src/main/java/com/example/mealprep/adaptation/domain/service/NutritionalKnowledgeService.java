package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import java.util.List;
import java.util.UUID;

/**
 * Upgradeable food-science seam. v1 (01e) is a lookup table seeded by repeatable migration; v2 is a
 * structured knowledge base; v3 is real-time tool-use. The interface is locked here in 01b; the v1
 * implementation, concrete knowledge tables, and upgrade are deferred.
 *
 * <p>01b ships {@link
 * com.example.mealprep.adaptation.domain.service.internal.NoopNutritionalKnowledgeService} as the
 * default-bean stub (registered via {@code @ConditionalOnMissingBean}); 01e replaces it with the
 * real implementation.
 *
 * <p>Per LLD §Service Interfaces lines 548-564; signatures verbatim from {@code
 * lld/adaptation-pipeline.md}.
 */
public interface NutritionalKnowledgeService {

  /** Pairings: "iron-rich + lemon = boosted absorption." */
  List<NutritionalPairingDto> lookupPairings(List<String> ingredientMappingKeys);

  /** Cooking-method bioavailability: "raw spinach > steamed for folate; reverse for lycopene." */
  List<MethodBioavailabilityDto> lookupMethodEffects(
      String ingredientMappingKey, List<String> methods);

  /** Soaks, ferments, prep windows. Surfaces as {@code PREP_LEAD_TIME} planner hints. */
  List<PrepRequirementDto> lookupPrepRequirements(List<String> ingredientMappingKeys);

  /** Absorption conflicts: "calcium blocks non-haem iron; don't pair this stir-fry with milk." */
  List<AbsorptionConflictDto> lookupConflicts(List<String> ingredientMappingKeys);

  /** Bulk pull for a whole recipe — assembles all four kinds for the LLM context. */
  NutritionalKnowledgeBundleDto lookupForRecipe(UUID versionId, List<String> ingredientMappingKeys);
}
