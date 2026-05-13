package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PlanConstraintsSnapshotDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Asserts the 01b skeleton: every write/read method throws {@link UnsupportedOperationException}
 * with a ticket marker — except {@link AdaptationService#sweepExpiredPendingChanges()} which
 * returns {@code 0} (the safe no-op for the inter-ticket {@code @Scheduled} window).
 *
 * <p>The repository mocks are passed as-is — no behaviour is exercised; only that the bean
 * constructs and routes calls to UOE.
 */
class AdaptationServiceImplSkeletonTest {

  private final AdaptationServiceImpl service =
      new AdaptationServiceImpl(
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository.class),
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.PendingChangeRepository.class),
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository.class),
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository
                  .class),
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository.class),
          Mockito.mock(
              com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository
                  .class));

  @Test
  void sweep_expired_pending_changes_returns_zero() {
    // Safe no-op for the scheduled cron job during the inter-ticket window.
    assertThat(service.sweepExpiredPendingChanges()).isZero();
  }

  @Test
  void enqueue_import_job_throws_uoe_with_ticket_01d_marker() {
    ImportJobRequest req =
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            com.example.mealprep.recipe.domain.entity.Catalogue.USER,
            com.example.mealprep.recipe.domain.entity.DataQuality.AI_GENERATED,
            null,
            null);
    assertThatThrownBy(() -> service.enqueueImportJob(req))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01d");
  }

  @Test
  void enqueue_feedback_job_throws_uoe_with_ticket_01c_marker() {
    FeedbackJobRequest req =
        new FeedbackJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "tastes flat",
            new FeedbackJobRequest.RatingDeltaDto(BigDecimal.valueOf(-1), null, null, null),
            UUID.randomUUID(),
            null);
    assertThatThrownBy(() -> service.enqueueFeedbackJob(req))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01c");
  }

  @Test
  void enqueue_data_model_change_jobs_throws_uoe_with_ticket_01d_marker() {
    DataModelJobRequest req =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.PREFERENCE,
            JsonNodeFactory.instance.objectNode(),
            Set.of(UUID.randomUUID()),
            UUID.randomUUID());
    assertThatThrownBy(() -> service.enqueueDataModelChangeJobs(req))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01d");
  }

  @Test
  void run_plan_time_refine_job_throws_uoe_with_ticket_01c_marker() {
    PlanTimeRefineDirectiveRequest req =
        new PlanTimeRefineDirectiveRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new PlanTimeRefineDirectiveRequest.RefineDirectiveDto(
                com.example.mealprep.adaptation.api.dto.DirectiveKind.COST_DELTA,
                "drop £2",
                JsonNodeFactory.instance.objectNode()),
            new PlanConstraintsSnapshotDto(
                JsonNodeFactory.instance.objectNode(),
                BigDecimal.valueOf(40),
                Set.of("oven"),
                java.util.Map.of("protein_g", BigDecimal.valueOf(120)),
                Instant.now()),
            UUID.randomUUID(),
            UUID.randomUUID());
    assertThatThrownBy(() -> service.runPlanTimeRefineJob(req))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01c");
  }

  @Test
  void accept_pending_change_throws_uoe_with_ticket_01d_marker() {
    AcceptPendingChangeRequest req = new AcceptPendingChangeRequest(null, 0L);
    assertThatThrownBy(() -> service.acceptPendingChange(UUID.randomUUID(), req, UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01d");
  }

  @Test
  void reject_pending_change_throws_uoe_with_ticket_01d_marker() {
    RejectPendingChangeRequest req = new RejectPendingChangeRequest(null);
    assertThatThrownBy(() -> service.rejectPendingChange(UUID.randomUUID(), req, UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01d");
  }

  @Test
  void emit_planner_hint_throws_uoe_with_ticket_01f_marker() {
    PlannerHintRequest req =
        new PlannerHintRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            HintType.PREP_LEAD_TIME,
            "soak overnight",
            JsonNodeFactory.instance.objectNode(),
            HintSeverity.INFO,
            null,
            UUID.randomUUID());
    assertThatThrownBy(() -> service.emitPlannerHint(req, UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ticket-01f");
  }

  @Test
  void query_methods_throw_uoe() {
    UUID id = UUID.randomUUID();
    org.springframework.data.domain.Pageable page =
        org.springframework.data.domain.PageRequest.of(0, 10);
    assertThatThrownBy(() -> service.listPendingForUser(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.listPendingHistoryForRecipe(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getPendingChange(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getJobsForRecipe(id, page))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getActiveJobsForUser(id, page))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getTracesForRecipe(id, page))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getTracesForPromptVersion("n", "v", page))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getTraceForJob(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getActiveHintsForVersion(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getActiveHintsForVersions(java.util.List.of(id)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.getMostRecentResultForRecipe(id))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
