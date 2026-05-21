package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileAuditEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TasteProfileVersionDto;
import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.api.mapper.TasteProfileMapper;
import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteProfileAuditLog;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.internal.TasteProfileDeltaApplier;
import com.example.mealprep.preference.domain.service.internal.TasteProfileServiceImpl;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.event.TasteProfileRefreshRequestedEvent;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit coverage for {@link TasteProfileServiceImpl}. The real {@link TasteProfileMapper}
 * (MapStruct-generated) and a real {@link ObjectMapper} are used (deterministic, no-IO);
 * repositories, event publisher, and the delta applier are mocked.
 */
@ExtendWith(MockitoExtension.class)
class TasteProfileServiceImplTest {

  @Mock private TasteProfileRepository tasteProfileRepository;
  @Mock private TasteProfileVersionRepository versionRepository;
  @Mock private TasteProfileAuditLogRepository auditLogRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private TasteProfileDeltaApplier deltaApplier;

  private final TasteProfileMapper mapper =
      new com.example.mealprep.preference.api.mapper.TasteProfileMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-20T10:00:00Z"), ZoneOffset.UTC);

  private TasteProfileServiceImpl service() {
    return new TasteProfileServiceImpl(
        tasteProfileRepository,
        versionRepository,
        auditLogRepository,
        mapper,
        eventPublisher,
        deltaApplier,
        objectMapper,
        fixedClock);
  }

  // ---------------- getTasteProfile ----------------

  @Test
  void getTasteProfile_whenAggregateExists_returnsDto() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    Optional<TasteProfileDto> result = service().getTasteProfile(userId);

    assertThat(result).isPresent();
    assertThat(result.get().userId()).isEqualTo(userId);
    assertThat(result.get().documentVersion()).isEqualTo(1);
    assertThat(result.get().tasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);
  }

  @Test
  void getTasteProfile_whenAggregateMissing_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getTasteProfile(userId)).isEmpty();
  }

  @Test
  void getTasteProfilesByUserIds_whenEmptyInput_returnsEmptyList_andDoesNotHitRepo() {
    assertThat(service().getTasteProfilesByUserIds(List.of())).isEmpty();
    verifyNoInteractions(tasteProfileRepository);
  }

  @Test
  void getTasteProfilesByUserIds_returnsMappedDtos() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(tasteProfileRepository.findByUserIdIn(List.of(a, b)))
        .thenReturn(List.of(TasteProfileTestData.aggregate(a), TasteProfileTestData.aggregate(b)));

    List<TasteProfileDto> result = service().getTasteProfilesByUserIds(List.of(a, b));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).userId()).isEqualTo(a);
    assertThat(result.get(1).userId()).isEqualTo(b);
  }

  // ---------------- getVersions / getVersion ----------------

  @Test
  void getVersions_whenNoProfile_returnsEmptyPage_andDoesNotQueryVersions() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    Page<TasteProfileVersionDto> result = service().getVersions(userId, PageRequest.of(0, 10));

    assertThat(result.getTotalElements()).isZero();
    verifyNoInteractions(versionRepository);
  }

  @Test
  void getVersion_whenNotFound_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(aggregate.getId(), 99))
        .thenReturn(Optional.empty());

    assertThat(service().getVersion(userId, 99)).isEmpty();
  }

  // ---------------- getAuditLog ----------------

  @Test
  void getAuditLog_whenNoProfile_returnsEmptyPage_andDoesNotQueryAudit() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    Page<TasteProfileAuditEntryDto> result = service().getAuditLog(userId, PageRequest.of(0, 10));

    assertThat(result.getTotalElements()).isZero();
    verifyNoInteractions(auditLogRepository);
  }

  @Test
  void getAuditLog_returnsMappedPage() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    TasteProfileAuditLog entry =
        TasteProfileAuditLog.builder()
            .id(UUID.randomUUID())
            .tasteProfile(aggregate)
            .actorUserId(userId)
            .actorType(ActorType.USER)
            .changeType(TasteProfileChangeType.MANUAL_OVERRIDE)
            .previousDocumentVersion(1)
            .newDocumentVersion(2)
            .summary("manual override")
            .traceId(null)
            .occurredAt(Instant.parse("2026-05-20T10:00:00Z"))
            .build();
    Pageable pageable = PageRequest.of(0, 10);
    when(auditLogRepository.findByTasteProfileIdOrderByOccurredAtDesc(aggregate.getId(), pageable))
        .thenReturn(new PageImpl<>(List.of(entry), pageable, 1));

    Page<TasteProfileAuditEntryDto> result = service().getAuditLog(userId, pageable);

    assertThat(result.getTotalElements()).isEqualTo(1L);
    TasteProfileAuditEntryDto dto = result.getContent().get(0);
    assertThat(dto.changeType()).isEqualTo(TasteProfileChangeType.MANUAL_OVERRIDE);
    assertThat(dto.actorUserId()).isEqualTo(userId);
    assertThat(dto.newDocumentVersion()).isEqualTo(2);
  }

  // ---------------- initialise ----------------

  @Test
  void initialise_whenAbsent_createsProfileAndWritesAuditAndVersion_andPublishesEvent() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(tasteProfileRepository.save(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    TasteProfileDto dto = service().initialise(userId);

    ArgumentCaptor<TasteProfile> saveCaptor = ArgumentCaptor.forClass(TasteProfile.class);
    verify(tasteProfileRepository).save(saveCaptor.capture());
    TasteProfile saved = saveCaptor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getDocumentVersion()).isEqualTo(1);
    assertThat(saved.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);

    ArgumentCaptor<TasteProfileAuditLog> auditCaptor =
        ArgumentCaptor.forClass(TasteProfileAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getChangeType())
        .isEqualTo(TasteProfileChangeType.INITIALIZED);
    assertThat(auditCaptor.getValue().getActorType()).isEqualTo(ActorType.USER);
    assertThat(auditCaptor.getValue().getPreviousDocumentVersion()).isNull();
    assertThat(auditCaptor.getValue().getNewDocumentVersion()).isEqualTo(1);

    ArgumentCaptor<TasteProfileVersion> versionCaptor =
        ArgumentCaptor.forClass(TasteProfileVersion.class);
    verify(versionRepository).save(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getTrigger()).isEqualTo(TasteProfileTrigger.MANUAL);
    assertThat(versionCaptor.getValue().getDocumentVersion()).isEqualTo(1);

    ArgumentCaptor<TasteProfileChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(TasteProfileChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().changeType()).isEqualTo(TasteProfileChangeType.INITIALIZED);
    assertThat(eventCaptor.getValue().scopeKind()).isEqualTo("taste-profile");
    assertThat(eventCaptor.getValue().scopeId()).isEqualTo(userId);

    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.documentVersion()).isEqualTo(1);
  }

  @Test
  void initialise_whenAlreadyPresent_returnsExistingWithoutWriting() {
    UUID userId = UUID.randomUUID();
    TasteProfile existing = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

    TasteProfileDto dto = service().initialise(userId);

    verify(tasteProfileRepository, never()).save(any(TasteProfile.class));
    verifyNoInteractions(auditLogRepository, versionRepository, eventPublisher);
    assertThat(dto.id()).isEqualTo(existing.getId());
  }

  // ---------------- applyManualOverride ----------------

  @Test
  void applyManualOverride_whenAbsent_throwsNotFound_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    UpdateTasteProfileRequest request = TasteProfileTestData.updateRequest(0L);

    assertThatThrownBy(() -> service().applyManualOverride(userId, request, userId))
        .isInstanceOf(TasteProfileNotFoundException.class);
    verifyNoInteractions(eventPublisher, auditLogRepository, versionRepository);
    verify(tasteProfileRepository, never()).saveAndFlush(any());
  }

  @Test
  void applyManualOverride_whenVersionMismatch_throws409_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    aggregate.setOptimisticVersion(3L);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    UpdateTasteProfileRequest stale = TasteProfileTestData.updateRequest(0L);

    assertThatThrownBy(() -> service().applyManualOverride(userId, stale, userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verifyNoInteractions(eventPublisher, auditLogRepository, versionRepository);
  }

  @Test
  void applyManualOverride_writesAuditAndVersion_bumpsDocumentVersion_publishesEvent() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateTasteProfileRequest request = TasteProfileTestData.updateRequest(0L);

    TasteProfileDto dto = service().applyManualOverride(userId, request, userId);

    assertThat(aggregate.getDocumentVersion()).isEqualTo(2);
    assertThat(aggregate.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);

    ArgumentCaptor<TasteProfileAuditLog> auditCaptor =
        ArgumentCaptor.forClass(TasteProfileAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getChangeType())
        .isEqualTo(TasteProfileChangeType.MANUAL_OVERRIDE);
    assertThat(auditCaptor.getValue().getPreviousDocumentVersion()).isEqualTo(1);
    assertThat(auditCaptor.getValue().getNewDocumentVersion()).isEqualTo(2);
    assertThat(auditCaptor.getValue().getActorType()).isEqualTo(ActorType.USER);

    ArgumentCaptor<TasteProfileVersion> versionCaptor =
        ArgumentCaptor.forClass(TasteProfileVersion.class);
    verify(versionRepository).save(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getTrigger()).isEqualTo(TasteProfileTrigger.MANUAL);
    assertThat(versionCaptor.getValue().getDocumentVersion()).isEqualTo(2);

    ArgumentCaptor<TasteProfileChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(TasteProfileChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().changeType())
        .isEqualTo(TasteProfileChangeType.MANUAL_OVERRIDE);
    assertThat(eventCaptor.getValue().documentVersion()).isEqualTo(2);

    assertThat(dto.documentVersion()).isEqualTo(2);
    assertThat(dto.document().version()).isEqualTo(2);
  }

  // ---------------- applyDeltas (stub) ----------------

  @Test
  void applyDeltas_callsStub_whichThrowsUnsupported() {
    UUID userId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(deltaApplier.apply(any(), any()))
        .thenThrow(
            new UnsupportedOperationException(
                "delta application landing in deferred ticket 01c-delta-applier"));

    ApplyTasteProfileDeltasRequest request =
        new ApplyTasteProfileDeltasRequest(
            List.of(), TasteProfileTrigger.BATCH, null, null, "cheap");

    assertThatThrownBy(() -> service().applyDeltas(userId, request))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("01c-delta-applier");
  }

  @Test
  void applyDeltas_whenProfileMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    ApplyTasteProfileDeltasRequest request =
        new ApplyTasteProfileDeltasRequest(
            List.of(), TasteProfileTrigger.BATCH, null, null, "cheap");

    assertThatThrownBy(() -> service().applyDeltas(userId, request))
        .isInstanceOf(TasteProfileNotFoundException.class);
    verifyNoInteractions(deltaApplier);
  }

  // ---------------- triggerRefresh ----------------

  @Test
  void triggerRefresh_whenProfileMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .triggerRefresh(
                        userId, new TriggerTasteProfileRefreshRequest(null, null), userId, null))
        .isInstanceOf(TasteProfileNotFoundException.class);
  }

  @Test
  void triggerRefresh_writesAuditRow_publishesRefreshRequestedEvent_doesNotBumpVersion() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    TasteProfile aggregate = TasteProfileTestData.aggregate(userId);
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));

    TasteProfileDto dto =
        service()
            .triggerRefresh(
                userId, new TriggerTasteProfileRefreshRequest("f-1", "f-99"), userId, traceId);

    assertThat(aggregate.getDocumentVersion()).isEqualTo(1);

    ArgumentCaptor<TasteProfileAuditLog> auditCaptor =
        ArgumentCaptor.forClass(TasteProfileAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getChangeType())
        .isEqualTo(TasteProfileChangeType.REFRESH_TRIGGERED);
    assertThat(auditCaptor.getValue().getTraceId()).isEqualTo(traceId);

    ArgumentCaptor<TasteProfileRefreshRequestedEvent> eventCaptor =
        ArgumentCaptor.forClass(TasteProfileRefreshRequestedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().userId()).isEqualTo(userId);
    assertThat(eventCaptor.getValue().feedbackRangeStart()).isEqualTo("f-1");
    assertThat(eventCaptor.getValue().feedbackRangeEnd()).isEqualTo("f-99");
    assertThat(eventCaptor.getValue().traceId()).isEqualTo(traceId);

    // No version snapshot — no document mutation occurred.
    verifyNoInteractions(versionRepository);
    assertThat(dto.documentVersion()).isEqualTo(1);
  }

  // ---------------- delta applier stub direct test ----------------

  @Test
  void deltaApplier_noopStub_throwsWithDeferredMessage() {
    TasteProfileDeltaApplier.NoopStub stub = new TasteProfileDeltaApplier.NoopStub();
    assertThatThrownBy(
            () ->
                stub.apply(
                    TasteProfileTestData.populatedDocument(1),
                    new ApplyTasteProfileDeltasRequest(
                        List.of(), TasteProfileTrigger.BATCH, null, null, "cheap")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(TasteProfileDeltaApplier.DEFERRED_MESSAGE);
  }
}
