package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import com.example.mealprep.adaptation.api.mapper.NutritionalKnowledgeMapper;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1 lookup-table implementation of {@link NutritionalKnowledgeService}. Reads the {@code
 * adaptation_nutritional_knowledge} reference table via {@link
 * NutritionalKnowledgeRepository#findIntersectingSubjects} (GIN-backed {@code subject_keys && cast
 * (:keys as text[])} intersect), filtered per {@link KnowledgeKind}.
 *
 * <p><b>Sparse-hit behaviour</b> (LLD line 573): an empty key list short-circuits to an empty list
 * with no DB call; zero matching rows return an empty list — never an error and never a WARN log.
 * The downstream LLM still produces useful output without curated facts; the WARN-on-cold-lookup
 * alternative was considered and rejected per the ticket.
 *
 * <p><b>{@code versionId} is intentionally unused in v1</b> (LLD line 553 / ticket step 12). The
 * parameter is carried on {@link #lookupForRecipe} so a future v2 can scope facts to "things
 * learned from this specific version" without an interface break; v1 keys purely off the ingredient
 * mapping keys.
 *
 * <p>Registered as a plain {@code @Component}: once this bean is in the context the
 * {@code @ConditionalOnMissingBean} on {@link NoopNutritionalKnowledgeConfiguration} no longer
 * fires, so the 01b Noop is superseded without being deleted (parallel-development safety net).
 */
@Component
@Transactional(readOnly = true)
public class NutritionalKnowledgeServiceImpl implements NutritionalKnowledgeService {

  private final NutritionalKnowledgeRepository repo;
  private final NutritionalKnowledgeMapper mapper;

  public NutritionalKnowledgeServiceImpl(
      NutritionalKnowledgeRepository repo, NutritionalKnowledgeMapper mapper) {
    this.repo = repo;
    this.mapper = mapper;
  }

  @Override
  public List<NutritionalPairingDto> lookupPairings(List<String> ingredientMappingKeys) {
    if (ingredientMappingKeys == null || ingredientMappingKeys.isEmpty()) {
      return List.of();
    }
    return repo
        .findIntersectingSubjects(
            KnowledgeKind.PAIRING.name(), ingredientMappingKeys.toArray(String[]::new))
        .stream()
        .map(mapper::toPairingDto)
        .toList();
  }

  @Override
  public List<MethodBioavailabilityDto> lookupMethodEffects(
      String ingredientMappingKey, List<String> methods) {
    if (ingredientMappingKey == null || ingredientMappingKey.isBlank()) {
      return List.of();
    }
    return repo
        .findIntersectingSubjects(
            KnowledgeKind.METHOD_BIOAVAILABILITY.name(), new String[] {ingredientMappingKey})
        .stream()
        .map(mapper::toMethodEffectDto)
        .filter(dto -> methods == null || methods.isEmpty() || methods.contains(dto.method()))
        .toList();
  }

  @Override
  public List<PrepRequirementDto> lookupPrepRequirements(List<String> ingredientMappingKeys) {
    if (ingredientMappingKeys == null || ingredientMappingKeys.isEmpty()) {
      return List.of();
    }
    return repo
        .findIntersectingSubjects(
            KnowledgeKind.SOAK_NEEDED.name(), ingredientMappingKeys.toArray(String[]::new))
        .stream()
        .map(mapper::toPrepRequirementDto)
        .toList();
  }

  @Override
  public List<AbsorptionConflictDto> lookupConflicts(List<String> ingredientMappingKeys) {
    if (ingredientMappingKeys == null || ingredientMappingKeys.isEmpty()) {
      return List.of();
    }
    return repo
        .findIntersectingSubjects(
            KnowledgeKind.ABSORPTION_CONFLICT.name(), ingredientMappingKeys.toArray(String[]::new))
        .stream()
        .map(mapper::toConflictDto)
        .toList();
  }

  /**
   * Composes the four lookups into a single bundle. {@code versionId} is accepted for forward
   * compatibility (see class Javadoc) but ignored in v1. A sparse / un-seeded knowledge table
   * yields an empty-but-non-null bundle on every list.
   */
  @Override
  public NutritionalKnowledgeBundleDto lookupForRecipe(
      UUID versionId, List<String> ingredientMappingKeys) {
    if (ingredientMappingKeys == null || ingredientMappingKeys.isEmpty()) {
      return new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of());
    }
    List<MethodBioavailabilityDto> methodEffects =
        ingredientMappingKeys.stream()
            .flatMap(key -> lookupMethodEffects(key, List.of()).stream())
            .toList();
    return new NutritionalKnowledgeBundleDto(
        lookupPairings(ingredientMappingKeys),
        methodEffects,
        lookupPrepRequirements(ingredientMappingKeys),
        lookupConflicts(ingredientMappingKeys));
  }
}
