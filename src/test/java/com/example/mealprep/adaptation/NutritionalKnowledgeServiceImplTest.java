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

  // ---------------- lookupMethodEffects ----------------

  @Test
  void lookupMethodEffects_null_key_returns_empty_and_skips_db() {
    assertThat(service.lookupMethodEffects(null, List.of("FRY"))).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupMethodEffects_blank_key_returns_empty_and_skips_db() {
    // Kills the `isBlank()` negated-conditional mutant: "   " must short-circuit.
    assertThat(service.lookupMethodEffects("   ", List.of())).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupMethodEffects_no_method_filter_returns_all_rows() throws Exception {
    NutritionalKnowledgeEntry fry = methodRow("FRY", "destroys vitamin C");
    NutritionalKnowledgeEntry steam = methodRow("STEAM", "preserves folate");
    when(repo.findIntersectingSubjects(
            KnowledgeKind.METHOD_BIOAVAILABILITY.name(), new String[] {"spinach"}))
        .thenReturn(List.of(fry, steam));

    // Empty filter list -> no filtering: both rows returned.
    var all = service.lookupMethodEffects("spinach", List.of());
    assertThat(all).extracting(d -> d.method()).containsExactlyInAnyOrder("FRY", "STEAM");

    // Null filter list -> no filtering either.
    var allNull = service.lookupMethodEffects("spinach", null);
    assertThat(allNull).hasSize(2);
  }

  @Test
  void lookupMethodEffects_filters_to_requested_methods_only() throws Exception {
    NutritionalKnowledgeEntry fry = methodRow("FRY", "destroys vitamin C");
    NutritionalKnowledgeEntry steam = methodRow("STEAM", "preserves folate");
    when(repo.findIntersectingSubjects(
            KnowledgeKind.METHOD_BIOAVAILABILITY.name(), new String[] {"spinach"}))
        .thenReturn(List.of(fry, steam));

    var filtered = service.lookupMethodEffects("spinach", List.of("STEAM"));

    assertThat(filtered).hasSize(1);
    assertThat(filtered.get(0).method()).isEqualTo("STEAM");
  }

  // ---------------- lookupPrepRequirements ----------------

  @Test
  void lookupPrepRequirements_null_keys_returns_empty_and_skips_db() {
    assertThat(service.lookupPrepRequirements(null)).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupPrepRequirements_empty_keys_returns_empty_and_skips_db() {
    assertThat(service.lookupPrepRequirements(List.of())).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupPrepRequirements_maps_rows_to_dtos() throws Exception {
    NutritionalKnowledgeEntry row =
        NutritionalKnowledgeEntry.builder()
            .id(UUID.randomUUID())
            .knowledgeKind(KnowledgeKind.SOAK_NEEDED)
            .subjectKeys(new String[] {"chickpea"})
            .payload(json.readTree("{\"requirement\":\"soak overnight\",\"leadTimeHours\":12}"))
            .confidenceTier("HIGH")
            .source("manual")
            .createdAt(Instant.now())
            .build();
    when(repo.findIntersectingSubjects(KnowledgeKind.SOAK_NEEDED.name(), new String[] {"chickpea"}))
        .thenReturn(List.of(row));

    var dtos = service.lookupPrepRequirements(List.of("chickpea"));

    assertThat(dtos).hasSize(1);
    assertThat(dtos.get(0).requirement()).isEqualTo("soak overnight");
    assertThat(dtos.get(0).leadTimeHours()).isEqualTo(12);
  }

  // ---------------- lookupConflicts ----------------

  @Test
  void lookupConflicts_null_keys_returns_empty_and_skips_db() {
    assertThat(service.lookupConflicts(null)).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupConflicts_empty_keys_returns_empty_and_skips_db() {
    assertThat(service.lookupConflicts(List.of())).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  @Test
  void lookupConflicts_maps_rows_to_dtos() throws Exception {
    NutritionalKnowledgeEntry row =
        NutritionalKnowledgeEntry.builder()
            .id(UUID.randomUUID())
            .knowledgeKind(KnowledgeKind.ABSORPTION_CONFLICT)
            .subjectKeys(new String[] {"calcium", "iron"})
            .payload(
                json.readTree(
                    "{\"conflict\":\"calcium blocks iron uptake\",\"severity\":\"MODERATE\"}"))
            .confidenceTier("MEDIUM")
            .source("manual")
            .createdAt(Instant.now())
            .build();
    when(repo.findIntersectingSubjects(
            KnowledgeKind.ABSORPTION_CONFLICT.name(), new String[] {"calcium", "iron"}))
        .thenReturn(List.of(row));

    var dtos = service.lookupConflicts(List.of("calcium", "iron"));

    assertThat(dtos).hasSize(1);
    assertThat(dtos.get(0).conflict()).isEqualTo("calcium blocks iron uptake");
    assertThat(dtos.get(0).severity()).isEqualTo("MODERATE");
  }

  @Test
  void lookupForRecipe_composes_real_rows_from_each_lookup() throws Exception {
    when(repo.findIntersectingSubjects(KnowledgeKind.PAIRING.name(), new String[] {"spinach"}))
        .thenReturn(
            List.of(pairingRow("iron pairs with vitamin C", new String[] {"spinach", "lemon"})));
    when(repo.findIntersectingSubjects(
            KnowledgeKind.METHOD_BIOAVAILABILITY.name(), new String[] {"spinach"}))
        .thenReturn(List.of(methodRow("STEAM", "preserves folate")));
    when(repo.findIntersectingSubjects(KnowledgeKind.SOAK_NEEDED.name(), new String[] {"spinach"}))
        .thenReturn(List.of());
    when(repo.findIntersectingSubjects(
            KnowledgeKind.ABSORPTION_CONFLICT.name(), new String[] {"spinach"}))
        .thenReturn(List.of());

    NutritionalKnowledgeBundleDto bundle =
        service.lookupForRecipe(UUID.randomUUID(), List.of("spinach"));

    // flatMap over keys must surface the method-effect row (kills the
    // lambda$lookupForRecipe$4 Stream.empty mutant and the L112 guard negation).
    assertThat(bundle.pairings()).hasSize(1);
    assertThat(bundle.pairings().get(0).description()).isEqualTo("iron pairs with vitamin C");
    assertThat(bundle.methodEffects()).hasSize(1);
    assertThat(bundle.methodEffects().get(0).method()).isEqualTo("STEAM");
    assertThat(bundle.prepRequirements()).isEmpty();
    assertThat(bundle.conflicts()).isEmpty();
  }

  @Test
  void lookupForRecipe_null_keys_returns_empty_bundle() {
    NutritionalKnowledgeBundleDto bundle = service.lookupForRecipe(UUID.randomUUID(), null);
    assertThat(bundle.pairings()).isEmpty();
    assertThat(bundle.methodEffects()).isEmpty();
    verify(repo, never()).findIntersectingSubjects(any(), any());
  }

  private NutritionalKnowledgeEntry methodRow(String method, String effect) throws Exception {
    return NutritionalKnowledgeEntry.builder()
        .id(UUID.randomUUID())
        .knowledgeKind(KnowledgeKind.METHOD_BIOAVAILABILITY)
        .subjectKeys(new String[] {"spinach"})
        .payload(
            json.readTree(
                "{\"method\":\"" + method + "\",\"effect\":\"" + effect + "\",\"magnitude\":0.5}"))
        .confidenceTier("HIGH")
        .source("manual")
        .createdAt(Instant.now())
        .build();
  }

  private NutritionalKnowledgeEntry pairingRow(String description, String[] keys) throws Exception {
    return NutritionalKnowledgeEntry.builder()
        .id(UUID.randomUUID())
        .knowledgeKind(KnowledgeKind.PAIRING)
        .subjectKeys(keys)
        .payload(json.readTree("{\"description\":\"" + description + "\",\"confidence\":0.7}"))
        .confidenceTier("HIGH")
        .source("manual")
        .createdAt(Instant.now())
        .build();
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
