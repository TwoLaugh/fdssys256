package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

  // Note: the original 01b skeleton test asserted UOE on the four trigger entries plus
  // accept/reject. After 01d wires those methods (skeleton ctor still passes null helpers, so
  // calls now throw NullPointerException at the first helper deref). The 01d test suite covers
  // the happy-path behaviour separately; here we only retain the UOE-marker check for
  // emitPlannerHint
  // (still 01f's territory) and sweepExpiredPendingChanges' return-zero contract.

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
  void query_methods_still_uoe_for_01f_territory() {
    UUID id = UUID.randomUUID();
    org.springframework.data.domain.Pageable page =
        org.springframework.data.domain.PageRequest.of(0, 10);
    // 01d wires listPendingForUser / listPendingHistoryForRecipe / getPendingChange — the
    // remaining read fan-out methods still throw UOE per ticket 01f's scope.
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
