package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * DB-backed: emitting a hint for V2 invalidates the active hint of the same type on V1 of the same
 * recipe (ticket 01f edge-case — new-version write makes prior-version hints stale).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PlannerHintInvalidationIT {

  @Autowired private AdaptationService adaptationService;
  @Autowired private PlannerHintRecordRepository repo;

  @AfterEach
  void cleanup() {
    repo.deleteAll();
  }

  private PlannerHintRequest req(UUID recipeId, UUID versionId) {
    return new PlannerHintRequest(
        recipeId,
        versionId,
        UUID.randomUUID(),
        HintType.PREP_LEAD_TIME,
        "soak overnight",
        JsonNodeFactory.instance.objectNode().put("lead_time_hours", 8),
        HintSeverity.INFO,
        null,
        UUID.randomUUID());
  }

  @Test
  void emitting_v2_hint_invalidates_v1_hint_of_same_type() {
    UUID recipeId = UUID.randomUUID();
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();

    PlannerHintDto h1 = adaptationService.emitPlannerHint(req(recipeId, v1), UUID.randomUUID());
    assertThat(repo.findActiveForVersion(v1)).hasSize(1);

    adaptationService.emitPlannerHint(req(recipeId, v2), UUID.randomUUID());

    assertThat(repo.findActiveForVersion(v1)).isEmpty();
    assertThat(repo.findActiveForVersion(v2)).hasSize(1);
    assertThat(repo.findById(h1.id()).orElseThrow().getInvalidatedAt()).isNotNull();
  }

  @Test
  void emit_with_null_emitted_by_job_id_persists() {
    UUID recipeId = UUID.randomUUID();
    UUID v1 = UUID.randomUUID();
    PlannerHintDto dto = adaptationService.emitPlannerHint(req(recipeId, v1), UUID.randomUUID());
    assertThat(repo.findById(dto.id()).orElseThrow().getEmittedByJobId()).isNull();
  }
}
