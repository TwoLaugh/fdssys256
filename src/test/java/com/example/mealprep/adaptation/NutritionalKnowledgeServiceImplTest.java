package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.mapper.NutritionalKnowledgeMapper;
import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.service.internal.NutritionalKnowledgeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class NutritionalKnowledgeServiceImplTest {

  private final NutritionalKnowledgeRepository repo =
      Mockito.mock(NutritionalKnowledgeRepository.class);
  private final NutritionalKnowledgeMapper mapper =
      Mappers.getMapper(NutritionalKnowledgeMapper.class);
  private final NutritionalKnowledgeServiceImpl service =
      new NutritionalKnowledgeServiceImpl(repo, mapper);
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void lookupPairings_empty_keys_returns_empty_and_skips_db() {
    assertThat(service.lookupPairings(List.of())).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupPairings_no_matching_rows_returns_empty_no_error() {
    when(repo.findIntersectingSubjects(KnowledgeKind.PAIRING.name(), new String[] {"iron"}))
        .thenReturn(List.of());
    assertThat(service.lookupPairings(List.of("iron"))).isEmpty();
  }

  @Test
  void lookupPairings_maps_rows_to_dtos() throws Exception {
    NutritionalKnowledgeEntry row =
        NutritionalKnowledgeEntry.builder()
            .id(UUID.randomUUID())
            .knowledgeKind(KnowledgeKind.PAIRING)
            .subjectKeys(new String[] {"iron", "lemon"})
            .payload(json.readTree("{\"description\":\"lemon boosts iron\",\"confidence\":0.8}"))
            .confidenceTier("HIGH")
            .source("manual")
            .createdAt(Instant.now())
            .build();
    when(repo.findIntersectingSubjects(
            KnowledgeKind.PAIRING.name(), new String[] {"iron", "lemon"}))
        .thenReturn(List.of(row));

    var dtos = service.lookupPairings(List.of("iron", "lemon"));

    assertThat(dtos).hasSize(1);
    assertThat(dtos.get(0).description()).isEqualTo("lemon boosts iron");
    assertThat(dtos.get(0).subjectKeys()).containsExactly("iron", "lemon");
    assertThat(dtos.get(0).confidence()).isEqualByComparingTo("0.8");
  }

  @Test
  void lookupForRecipe_empty_keys_returns_empty_bundle() {
    NutritionalKnowledgeBundleDto bundle = service.lookupForRecipe(UUID.randomUUID(), List.of());
    assertThat(bundle.pairings()).isEmpty();
    assertThat(bundle.methodEffects()).isEmpty();
    assertThat(bundle.prepRequirements()).isEmpty();
    assertThat(bundle.conflicts()).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupForRecipe_composes_all_four_lookups() {
    when(repo.findIntersectingSubjects(any(), any())).thenReturn(List.of());
    NutritionalKnowledgeBundleDto bundle =
        service.lookupForRecipe(UUID.randomUUID(), List.of("spinach"));
    assertThat(bundle).isNotNull();
    assertThat(bundle.pairings()).isEmpty();
    assertThat(bundle.conflicts()).isEmpty();
  }
}
