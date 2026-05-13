package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import com.example.mealprep.adaptation.domain.service.internal.NoopNutritionalKnowledgeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Smoke for {@link NoopNutritionalKnowledgeService}. Every method must return an empty list or
 * empty bundle so the pipeline degrades gracefully when no curated facts are seeded.
 *
 * <p>The SPI-Noop bean wiring (the {@code @ConditionalOnMissingBean @Bean} factory) is exercised
 * implicitly by every other {@code @SpringBootTest} in the suite — the bean wires successfully if
 * the context starts. The override-with-{@code @TestConfiguration} assertion is deferred to
 * ticket-01e when a real impl exists.
 */
class NoopNutritionalKnowledgeServiceTest {

  private final NutritionalKnowledgeService service = new NoopNutritionalKnowledgeService();

  @Test
  void lookup_pairings_returns_empty() {
    List<NutritionalPairingDto> result = service.lookupPairings(List.of("spinach", "lemon"));
    assertThat(result).isEmpty();
  }

  @Test
  void lookup_method_effects_returns_empty() {
    List<MethodBioavailabilityDto> result =
        service.lookupMethodEffects("spinach", List.of("raw", "steam"));
    assertThat(result).isEmpty();
  }

  @Test
  void lookup_prep_requirements_returns_empty() {
    List<PrepRequirementDto> result = service.lookupPrepRequirements(List.of("dried_beans"));
    assertThat(result).isEmpty();
  }

  @Test
  void lookup_conflicts_returns_empty() {
    List<AbsorptionConflictDto> result = service.lookupConflicts(List.of("milk", "non_haem_iron"));
    assertThat(result).isEmpty();
  }

  @Test
  void lookup_for_recipe_returns_empty_bundle() {
    NutritionalKnowledgeBundleDto bundle =
        service.lookupForRecipe(UUID.randomUUID(), List.of("spinach"));
    assertThat(bundle.pairings()).isEmpty();
    assertThat(bundle.methodEffects()).isEmpty();
    assertThat(bundle.prepRequirements()).isEmpty();
    assertThat(bundle.conflicts()).isEmpty();
  }

  @Test
  void empty_input_lists_still_return_empty_results() {
    assertThat(service.lookupPairings(List.of())).isEmpty();
    assertThat(service.lookupConflicts(List.of())).isEmpty();
    assertThat(service.lookupPrepRequirements(List.of())).isEmpty();
  }
}
