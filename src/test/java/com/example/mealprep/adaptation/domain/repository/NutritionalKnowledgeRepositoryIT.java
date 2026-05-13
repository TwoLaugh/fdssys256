package com.example.mealprep.adaptation.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the GIN-backed native intersect query {@code subject_keys && cast(:keys as text[])}.
 * Two rows with {@code {a,b}} and {@code {b,c}}; queries should partition by overlap correctly and
 * filter by {@code knowledge_kind} simultaneously.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class NutritionalKnowledgeRepositoryIT {

  @Autowired private NutritionalKnowledgeRepository repository;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    repository.deleteAll();
  }

  @Test
  void findIntersectingSubjects_returns_overlapping_rows() throws Exception {
    repository.saveAndFlush(entry(KnowledgeKind.PAIRING, new String[] {"a", "b"}));
    repository.saveAndFlush(entry(KnowledgeKind.PAIRING, new String[] {"b", "c"}));
    repository.saveAndFlush(entry(KnowledgeKind.PAIRING, new String[] {"x", "y"}));

    List<NutritionalKnowledgeEntry> hits =
        repository.findIntersectingSubjects(KnowledgeKind.PAIRING.name(), new String[] {"b"});

    assertThat(hits).hasSize(2);
    assertThat(hits)
        .allSatisfy(row -> assertThat(row.getSubjectKeys()).containsAnyOf("a", "b", "c"));
  }

  @Test
  void findIntersectingSubjects_filters_by_kind() throws Exception {
    repository.saveAndFlush(entry(KnowledgeKind.PAIRING, new String[] {"a"}));
    repository.saveAndFlush(entry(KnowledgeKind.SOAK_NEEDED, new String[] {"a"}));

    List<NutritionalKnowledgeEntry> hits =
        repository.findIntersectingSubjects(KnowledgeKind.SOAK_NEEDED.name(), new String[] {"a"});

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).getKnowledgeKind()).isEqualTo(KnowledgeKind.SOAK_NEEDED);
  }

  @Test
  void findIntersectingSubjects_returns_empty_when_no_overlap() throws Exception {
    repository.saveAndFlush(entry(KnowledgeKind.PAIRING, new String[] {"a", "b"}));
    List<NutritionalKnowledgeEntry> hits =
        repository.findIntersectingSubjects(KnowledgeKind.PAIRING.name(), new String[] {"z"});
    assertThat(hits).isEmpty();
  }

  private NutritionalKnowledgeEntry entry(KnowledgeKind kind, String[] subjects) throws Exception {
    return NutritionalKnowledgeEntry.builder()
        .id(UUID.randomUUID())
        .knowledgeKind(kind)
        .subjectKeys(subjects)
        .payload(objectMapper.readTree("{}"))
        .confidenceTier("MEDIUM")
        .source("manual")
        .createdAt(Instant.now())
        .build();
  }
}
