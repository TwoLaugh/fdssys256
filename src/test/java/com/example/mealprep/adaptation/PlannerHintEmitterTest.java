package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

class PlannerHintEmitterTest {

  private final PlannerHintRecordRepository repo = Mockito.mock(PlannerHintRecordRepository.class);
  private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);
  private final PlannerHintEmitter emitter = new PlannerHintEmitter(repo, events);

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger emitterLogger;

  @BeforeEach
  void attachLogAppender() {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    emitterLogger = ctx.getLogger(PlannerHintEmitter.class);
    appender = new ListAppender<>();
    appender.start();
    emitterLogger.addAppender(appender);
    emitterLogger.setLevel(Level.INFO);
  }

  @AfterEach
  void detachLogAppender() {
    emitterLogger.detachAppender(appender);
  }

  private long infoLogCount() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).count();
  }

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

  // ---------------------------------------------------------------------------
  // Log-conditional mutants (PIT SURVIVED): emit L54 `invalidated > 0` and
  // invalidateHintsForOldVersion L99 `n > 0` gate ONLY a LOG.info. The
  // ConditionalsBoundary (`> 0` -> `>= 0`) and NegateConditionals mutants are
  // observable only via the log, so we assert exact INFO-log presence/absence
  // around the boundary value 0.
  // ---------------------------------------------------------------------------

  @Test
  void emit_logs_invalidation_info_only_when_priors_were_invalidated() {
    when(repo.invalidateForRecipeTypeOnOtherVersions(any(), any(), any(), any(Instant.class)))
        .thenReturn(2);
    emitter.emit(req(UUID.randomUUID(), UUID.randomUUID(), null), null);
    assertThat(infoLogCount()).isEqualTo(1L);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("planner-hint emit invalidated 2 prior-version hint(s)");
  }

  @Test
  void emit_does_not_log_when_zero_priors_invalidated() {
    // invalidated == 0: original SKIPS the log. ConditionalsBoundary (`>=0`) or
    // NegateConditionals would log here — assert zero INFO records to kill both.
    when(repo.invalidateForRecipeTypeOnOtherVersions(any(), any(), any(), any(Instant.class)))
        .thenReturn(0);
    emitter.emit(req(UUID.randomUUID(), UUID.randomUUID(), null), null);
    assertThat(infoLogCount()).isZero();
  }

  @Test
  void invalidate_old_version_logs_info_only_when_rows_touched() {
    UUID old = UUID.randomUUID();
    when(repo.invalidateForOldVersion(eq(old), any(Instant.class))).thenReturn(3);
    emitter.invalidateHintsForOldVersion(old);
    assertThat(infoLogCount()).isEqualTo(1L);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("invalidated 3 hint(s) for superseded versionId=" + old);
  }

  @Test
  void invalidate_old_version_does_not_log_when_zero_rows_touched() {
    UUID old = UUID.randomUUID();
    when(repo.invalidateForOldVersion(eq(old), any(Instant.class))).thenReturn(0);
    emitter.invalidateHintsForOldVersion(old);
    assertThat(infoLogCount()).isZero();
  }
}
