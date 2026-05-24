package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.spi.internal.RecipeFeedbackReverterImpl;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RevertContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeFeedbackReverterImpl} — cancel-pending vs log-only (feedback-01h
 * edge-case checklist: pending change cancelled / already applied / no handle, plus never-throw).
 */
class RecipeFeedbackReverterTest {

  private final AdaptationService adaptationService = mock(AdaptationService.class);
  private final PendingChangeRepository pendingChangeRepository =
      mock(PendingChangeRepository.class);
  private final RecipeFeedbackReverterImpl reverter =
      new RecipeFeedbackReverterImpl(adaptationService, pendingChangeRepository);

  @Test
  void revert_pendingChangeAwaitingApproval_cancelsViaReject() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    PendingChange pc = pending(userId, jobId, PendingChangeStatus.PENDING);
    when(pendingChangeRepository.findByJobId(jobId)).thenReturn(Optional.of(pc));

    reverter.revert(ctxWithJob(userId, jobId));

    verify(adaptationService)
        .rejectPendingChange(eq(pc.getId()), any(RejectPendingChangeRequest.class), eq(userId));
  }

  @Test
  void revert_pendingChangeAlreadyApplied_isLogOnlyAndDoesNotReject() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    PendingChange pc = pending(userId, jobId, PendingChangeStatus.ACCEPTED);
    when(pendingChangeRepository.findByJobId(jobId)).thenReturn(Optional.of(pc));

    reverter.revert(ctxWithJob(userId, jobId));

    verify(adaptationService, never())
        .rejectPendingChange(any(), any(RejectPendingChangeRequest.class), any());
  }

  @Test
  void revert_noPendingChangeForJob_isLogOnly() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(pendingChangeRepository.findByJobId(jobId)).thenReturn(Optional.empty());

    reverter.revert(ctxWithJob(userId, jobId));

    verify(adaptationService, never())
        .rejectPendingChange(any(), any(RejectPendingChangeRequest.class), any());
  }

  @Test
  void revert_noJobHandle_isLogOnlyAndTouchesNothing() {
    RevertContext ctx =
        new RevertContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Destination.RECIPE,
            null,
            JsonNodeFactory.instance.objectNode());

    reverter.revert(ctx);

    verifyNoInteractions(adaptationService);
  }

  @Test
  void revert_rejectRacesToTerminalState_swallows422AndDoesNotThrow() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    PendingChange pc = pending(userId, jobId, PendingChangeStatus.PENDING);
    when(pendingChangeRepository.findByJobId(jobId)).thenReturn(Optional.of(pc));
    when(adaptationService.rejectPendingChange(
            eq(pc.getId()), any(RejectPendingChangeRequest.class), eq(userId)))
        .thenThrow(new PendingChangeNotPendingException("pending change is ACCEPTED"));

    assertThatCode(() -> reverter.revert(ctxWithJob(userId, jobId))).doesNotThrowAnyException();
  }

  @Test
  void revert_rejectThrowsNotFound_swallowsAndDoesNotThrow() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    PendingChange pc = pending(userId, jobId, PendingChangeStatus.PENDING);
    when(pendingChangeRepository.findByJobId(jobId)).thenReturn(Optional.of(pc));
    when(adaptationService.rejectPendingChange(
            eq(pc.getId()), any(RejectPendingChangeRequest.class), eq(userId)))
        .thenThrow(new PendingChangeNotFoundException("gone"));

    assertThatCode(() -> reverter.revert(ctxWithJob(userId, jobId))).doesNotThrowAnyException();
  }

  private static RevertContext ctxWithJob(UUID userId, UUID jobId) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("status", "DISPATCHED");
    result.put("jobId", jobId.toString());
    return new RevertContext(
        UUID.randomUUID(), userId, UUID.randomUUID(), Destination.RECIPE, null, result);
  }

  private static PendingChange pending(UUID userId, UUID jobId, PendingChangeStatus status) {
    Instant now = Instant.now();
    return PendingChange.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(userId)
        .jobId(jobId)
        .traceId(UUID.randomUUID())
        .changeDimension(ChangeDimension.SALT_LEVEL)
        .proposedDiff(JsonNodeFactory.instance.objectNode())
        .proposedClassification(AdaptationClassification.BRANCH)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning("seeded")
        .confidence(new BigDecimal("0.900"))
        .impactScore(new BigDecimal("0.500"))
        .promptTemplateVersion("v1")
        .status(status)
        .createdAt(now)
        .expiresAt(now.plusSeconds(86_400))
        .optimisticVersion(0L)
        .build();
  }
}
