package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SPI-Noop default implementation of {@link NutritionalKnowledgeService}. Every method returns an
 * empty list / empty bundle so the adaptation pipeline degrades gracefully when no curated
 * food-science facts are seeded — the LLM still produces useful output without them.
 *
 * <p>Wired via {@link NoopNutritionalKnowledgeConfiguration}'s {@code @ConditionalOnMissingBean}
 * {@code @Bean} method (NOT a {@code @Service} annotation on this class). Per decisions/0010
 * round-5 bug-2: putting {@code @Component @ConditionalOnMissingBean} on the same class causes the
 * conditional to fire at component-scan time, before other beans register — the configuration-style
 * factory method delays the check until bean-definition resolution.
 *
 * <p>Lives under {@code internal/} to make the module-boundary intent clear — replaced by the real
 * {@code NutritionalKnowledgeServiceImpl} in ticket-01e.
 */
public class NoopNutritionalKnowledgeService implements NutritionalKnowledgeService {

  @Override
  public List<NutritionalPairingDto> lookupPairings(List<String> ingredientMappingKeys) {
    return Collections.emptyList();
  }

  @Override
  public List<MethodBioavailabilityDto> lookupMethodEffects(
      String ingredientMappingKey, List<String> methods) {
    return Collections.emptyList();
  }

  @Override
  public List<PrepRequirementDto> lookupPrepRequirements(List<String> ingredientMappingKeys) {
    return Collections.emptyList();
  }

  @Override
  public List<AbsorptionConflictDto> lookupConflicts(List<String> ingredientMappingKeys) {
    return Collections.emptyList();
  }

  @Override
  public NutritionalKnowledgeBundleDto lookupForRecipe(
      UUID versionId, List<String> ingredientMappingKeys) {
    return new NutritionalKnowledgeBundleDto(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList());
  }
}
