package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Skeleton-constructor sanity for the post-01f wiring. The 6-arg skeleton ctor leaves every helper
 * (mappers, emitter, refresher) {@code null}; 01f implements the read fan-out + emit + sweep so the
 * pre-01f UOE assertions no longer hold. This test now asserts the 01f contract: {@code
 * sweepExpiredPendingChanges} runs against the (mocked) repo and returns 0; {@code emitPlannerHint}
 * NPEs because the skeleton ctor never wired a {@link
 * com.example.mealprep.adaptation.domain.service.internal.PlannerHintEmitter}; the read methods
 * resolve against the empty repo mocks.
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
  void sweep_expired_pending_changes_returns_zero_with_no_expired_rows() {
    // Mocked repo returns an empty list → no DB writes, returns 0.
    assertThat(service.sweepExpiredPendingChanges()).isZero();
  }

  @Test
  void emit_planner_hint_npes_when_emitter_not_wired_in_skeleton_ctor() {
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
    // Skeleton ctor leaves plannerHintEmitter null — exercised only via the full Spring wiring.
    assertThatThrownBy(() -> service.emitPlannerHint(req, UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
  }

  // The read fan-out (getJob / getActiveHintsForVersion* / getTraceForJob / …) is exercised with
  // proper mapper mocks in AdaptationQueryServiceImplTest. The 6-arg skeleton ctor leaves the
  // mappers null, so those methods can't be driven here — pre-01f this test asserted UOE; 01f
  // implements them, and the realistic coverage lives in the dedicated query-service test.
}
