package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.api.mapper.PendingChangeMapper;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.example.mealprep.adaptation.event.PendingChangeAcceptedEvent;
import com.example.mealprep.adaptation.event.PendingChangeRejectedEvent;
import com.example.mealprep.adaptation.exception.PendingChangeExpiredException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.exception.PendingChangeSupersededException;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Unit tests for the accept / reject pending-change lifecycle on {@link AdaptationServiceImpl}. */
class PendingChangeLifecycleTest {

  @Test
  void accept_happy_path_writes_through_and_publishes_event() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().plusSeconds(86_400));
    pc.setOptimisticVersion(7L);

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));

    RecipeWriteApi writeApi = mock(RecipeWriteApi.class);
    UUID newVersionId = UUID.randomUUID();
    when(writeApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(newVersionId, pc.getBaseBranchId()));

    PendingChangeMapper mapper = mock(PendingChangeMapper.class);
    when(mapper.toDto(any(PendingChange.class)))
        .thenAnswer(inv -> dtoFrom((PendingChange) inv.getArgument(0)));

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    AdaptationServiceImpl svc = svc(repo, writeApi, mapper, events);

    PendingChangeDto out =
        svc.acceptPendingChange(pc.getId(), new AcceptPendingChangeRequest(null, 7L), userId);

    assertThat(out).isNotNull();
    assertThat(pc.getStatus()).isEqualTo(PendingChangeStatus.ACCEPTED);
    assertThat(pc.getAcceptedVersionId()).isEqualTo(newVersionId);
    assertThat(pc.getResolvedAt()).isNotNull();
    verify(events).publishEvent(any(PendingChangeAcceptedEvent.class));
  }

  @Test
  void accept_with_user_edits_sets_modified_status() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().plusSeconds(86_400));

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));
    RecipeWriteApi writeApi = mock(RecipeWriteApi.class);
    when(writeApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(UUID.randomUUID(), pc.getBaseBranchId()));
    PendingChangeMapper mapper = mock(PendingChangeMapper.class);
    when(mapper.toDto(any(PendingChange.class)))
        .thenAnswer(inv -> dtoFrom((PendingChange) inv.getArgument(0)));

    AdaptationServiceImpl svc = svc(repo, writeApi, mapper, mock(ApplicationEventPublisher.class));
    svc.acceptPendingChange(
        pc.getId(),
        new AcceptPendingChangeRequest(JsonNodeFactory.instance.objectNode(), 0L),
        userId);

    assertThat(pc.getStatus()).isEqualTo(PendingChangeStatus.MODIFIED);
    assertThat(pc.getUserEdits()).isNotNull();
  }

  @Test
  void accept_on_expired_writes_resolved_at_and_throws_422() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().minusSeconds(10));

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));

    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () ->
                svc.acceptPendingChange(
                    pc.getId(), new AcceptPendingChangeRequest(null, 0L), userId))
        .isInstanceOf(PendingChangeExpiredException.class);
    assertThat(pc.getStatus()).isEqualTo(PendingChangeStatus.EXPIRED);
    assertThat(pc.getResolvedAt()).isNotNull();
  }

  @Test
  void accept_on_already_rejected_throws_422_not_pending() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.REJECTED);
    pc.setResolvedAt(Instant.now().minusSeconds(60));

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));

    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () ->
                svc.acceptPendingChange(
                    pc.getId(), new AcceptPendingChangeRequest(null, 0L), userId))
        .isInstanceOf(PendingChangeNotPendingException.class);
  }

  @Test
  void accept_on_superseded_throws_409() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().plusSeconds(86_400));
    pc.setSupersededBy(UUID.randomUUID());

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));

    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () ->
                svc.acceptPendingChange(
                    pc.getId(), new AcceptPendingChangeRequest(null, 0L), userId))
        .isInstanceOf(PendingChangeSupersededException.class);
  }

  @Test
  void accept_with_stale_optimistic_version_throws_optimistic_lock() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().plusSeconds(86_400));
    pc.setOptimisticVersion(5L);

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () ->
                svc.acceptPendingChange(
                    pc.getId(), new AcceptPendingChangeRequest(null, 99L), userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void accept_for_other_user_throws_404_no_leak() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    PendingChange pc = pending(userA, PendingChangeStatus.PENDING);
    pc.setExpiresAt(Instant.now().plusSeconds(86_400));

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () ->
                svc.acceptPendingChange(
                    pc.getId(), new AcceptPendingChangeRequest(null, 0L), userB))
        .isInstanceOf(PendingChangeNotFoundException.class);
  }

  @Test
  void reject_happy_path_publishes_event() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.PENDING);

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    when(repo.saveAndFlush(any(PendingChange.class))).thenAnswer(inv -> inv.getArgument(0));
    PendingChangeMapper mapper = mock(PendingChangeMapper.class);
    when(mapper.toDto(any(PendingChange.class)))
        .thenAnswer(inv -> dtoFrom((PendingChange) inv.getArgument(0)));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    AdaptationServiceImpl svc = svc(repo, mock(RecipeWriteApi.class), mapper, events);
    svc.rejectPendingChange(pc.getId(), new RejectPendingChangeRequest("not for me"), userId);

    assertThat(pc.getStatus()).isEqualTo(PendingChangeStatus.REJECTED);
    assertThat(pc.getResolvedAt()).isNotNull();
    ArgumentCaptor<PendingChangeRejectedEvent> capt =
        ArgumentCaptor.forClass(PendingChangeRejectedEvent.class);
    verify(events).publishEvent(capt.capture());
    assertThat(capt.getValue().pendingChangeId()).isEqualTo(pc.getId());
  }

  @Test
  void reject_on_already_rejected_throws_422() {
    UUID userId = UUID.randomUUID();
    PendingChange pc = pending(userId, PendingChangeStatus.REJECTED);

    PendingChangeRepository repo = mock(PendingChangeRepository.class);
    when(repo.findById(pc.getId())).thenReturn(Optional.of(pc));
    AdaptationServiceImpl svc =
        svc(
            repo,
            mock(RecipeWriteApi.class),
            mock(PendingChangeMapper.class),
            mock(ApplicationEventPublisher.class));

    assertThatThrownBy(
            () -> svc.rejectPendingChange(pc.getId(), new RejectPendingChangeRequest(null), userId))
        .isInstanceOf(PendingChangeNotPendingException.class);
  }

  // -------- helpers -----------------------------------------------------------------------------

  private static PendingChange pending(UUID userId, PendingChangeStatus status) {
    return PendingChange.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(userId)
        .jobId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .changeDimension(ChangeDimension.SALT_LEVEL)
        .proposedDiff(JsonNodeFactory.instance.objectNode())
        .proposedClassification(AdaptationClassification.VERSION)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning("r")
        .nutritionalNotes("")
        .confidence(BigDecimal.valueOf(0.8))
        .impactScore(BigDecimal.valueOf(0.5))
        .promptTemplateVersion("v0")
        .status(status)
        .createdAt(Instant.now().minusSeconds(60))
        .expiresAt(Instant.now().plusSeconds(86_400))
        .build();
  }

  private static RecipeVersionDto versionDto(UUID id, UUID branchId) {
    return new RecipeVersionDto(
        id,
        branchId,
        1,
        UUID.randomUUID(),
        com.example.mealprep.recipe.domain.entity.VersionTrigger.ADAPTATION_PIPELINE,
        "adapted",
        "pending",
        Instant.now(),
        "adapter",
        UUID.randomUUID(),
        List.of(),
        List.of(),
        null,
        null,
        List.of());
  }

  private static PendingChangeDto dtoFrom(PendingChange pc) {
    return new PendingChangeDto(
        pc.getId(),
        pc.getRecipeId(),
        pc.getUserId(),
        pc.getJobId(),
        pc.getTraceId(),
        pc.getChangeDimension(),
        pc.getProposedClassification(),
        pc.getBaseVersionId(),
        pc.getBaseBranchId(),
        pc.getProposedDiff(),
        pc.getReasoning(),
        pc.getNutritionalNotes(),
        pc.getConfidence(),
        pc.getImpactScore(),
        pc.getPromptTemplateVersion(),
        pc.getStatus(),
        pc.getSupersededBy(),
        pc.getAcceptedVersionId(),
        pc.getUserEdits(),
        pc.getCreatedAt(),
        pc.getExpiresAt(),
        pc.getResolvedAt(),
        pc.getOptimisticVersion());
  }

  private static AdaptationServiceImpl svc(
      PendingChangeRepository repo,
      RecipeWriteApi writeApi,
      PendingChangeMapper mapper,
      ApplicationEventPublisher events) {
    return new AdaptationServiceImpl(
        mock(AdaptationJobRepository.class),
        repo,
        mock(AdaptationTraceRepository.class),
        mock(AdaptationFingerprintRepository.class),
        mock(PlannerHintRecordRepository.class),
        mock(NutritionalKnowledgeRepository.class),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        events,
        writeApi,
        mapper,
        config(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static AdaptationConfig config() {
    return new AdaptationConfig(
        5,
        10_000,
        8_000,
        12_000,
        3,
        3,
        14,
        new BigDecimal("0.50"),
        new BigDecimal("2.00"),
        null,
        30,
        "0 0 4 * * *",
        "0 30 4 * * *");
  }
}
