package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.internal.PlannerHintEmitter;
import com.example.mealprep.adaptation.event.PlannerHintEmittedEvent;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class PlannerHintEmitterTest {

  private final PlannerHintRecordRepository repo = Mockito.mock(PlannerHintRecordRepository.class);
  private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);
  private final PlannerHintEmitter emitter = new PlannerHintEmitter(repo, events);

  private static PlannerHintRequest req(UUID recipeId, UUID versionId, UUID jobId) {
    return new PlannerHintRequest(
        recipeId,
        versionId,
        UUID.randomUUID(),
        HintType.PREP_LEAD_TIME,
        "soak overnight",
        JsonNodeFactory.instance.objectNode().put("lead_time_hours", 8),
        HintSeverity.INFO,
        jobId,
        UUID.randomUUID());
  }

  @Test
  void emit_persists_row_invalidates_priors_and_publishes_event() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(repo.invalidateForRecipeTypeOnOtherVersions(
            eq(recipeId), eq(HintType.PREP_LEAD_TIME), eq(versionId), any(Instant.class)))
        .thenReturn(2);

    PlannerHintRecord saved = emitter.emit(req(recipeId, versionId, jobId), jobId);

    assertThat(saved.getRecipeId()).isEqualTo(recipeId);
    assertThat(saved.getVersionId()).isEqualTo(versionId);
    assertThat(saved.getEmittedByJobId()).isEqualTo(jobId);
    assertThat(saved.getCreatedAt()).isNotNull();
    verify(repo)
        .invalidateForRecipeTypeOnOtherVersions(
            eq(recipeId), eq(HintType.PREP_LEAD_TIME), eq(versionId), any(Instant.class));
    verify(repo).save(any(PlannerHintRecord.class));
    ArgumentCaptor<PlannerHintEmittedEvent> evt =
        ArgumentCaptor.forClass(PlannerHintEmittedEvent.class);
    verify(events).publishEvent(evt.capture());
    assertThat(evt.getValue().recipeId()).isEqualTo(recipeId);
    assertThat(evt.getValue().versionId()).isEqualTo(versionId);
  }

  @Test
  void emit_with_null_job_id_for_planner_noticed_hint() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    PlannerHintRecord saved = emitter.emit(req(recipeId, versionId, null), null);
    assertThat(saved.getEmittedByJobId()).isNull();
    verify(events).publishEvent(any(PlannerHintEmittedEvent.class));
  }

  @Test
  void emit_with_no_priors_invalidated_still_persists_and_publishes() {
    // invalidated == 0 path (the false side of `invalidated > 0`): the row is still
    // saved and the event still published — only the info log is skipped.
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(repo.invalidateForRecipeTypeOnOtherVersions(any(), any(), any(), any(Instant.class)))
        .thenReturn(0);

    PlannerHintRecord saved = emitter.emit(req(recipeId, versionId, null), null);

    assertThat(saved.getRecipeId()).isEqualTo(recipeId);
    verify(repo).save(any(PlannerHintRecord.class));
    verify(events).publishEvent(any(PlannerHintEmittedEvent.class));
  }

  @Test
  void invalidate_hints_for_old_version_returns_zero_when_no_rows_touched() {
    UUID old = UUID.randomUUID();
    when(repo.invalidateForOldVersion(eq(old), any(Instant.class))).thenReturn(0);
    assertThat(emitter.invalidateHintsForOldVersion(old)).isZero();
    verify(repo).invalidateForOldVersion(eq(old), any(Instant.class));
  }

  @Test
  void invalidate_hints_for_old_version_delegates_to_repo() {
    UUID old = UUID.randomUUID();
    when(repo.invalidateForOldVersion(eq(old), any(Instant.class))).thenReturn(3);
    assertThat(emitter.invalidateHintsForOldVersion(old)).isEqualTo(3);
  }

  @Test
  void invalidate_hints_for_null_version_is_a_noop() {
    assertThat(emitter.invalidateHintsForOldVersion(null)).isZero();
    verify(repo, never()).invalidateForOldVersion(any(), any());
  }
}
