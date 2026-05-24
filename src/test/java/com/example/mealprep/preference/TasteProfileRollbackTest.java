package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.mapper.TasteProfileMapper;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
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
import com.example.mealprep.preference.domain.service.internal.TasteProfileBudgetGuard;
import com.example.mealprep.preference.domain.service.internal.TasteProfileDeltaApplier;
import com.example.mealprep.preference.domain.service.internal.TasteProfileServiceImpl;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.event.TasteProfileRollbackReplayRequestedEvent;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit coverage for {@link TasteProfileServiceImpl#rollbackTasteProfile} (preference-01h). Mocks
 * the repositories + event publisher; uses real MapStruct mapper + ObjectMapper (deterministic,
 * no-IO). The IT layer ({@code TasteProfileRollbackReplayIT}) verifies AFTER_COMMIT event delivery
 * + DB state.
 */
@ExtendWith(MockitoExtension.class)
class TasteProfileRollbackTest {

  @Mock private TasteProfileRepository tasteProfileRepository;
  @Mock private TasteProfileVersionRepository versionRepository;
  @Mock private TasteProfileAuditLogRepository auditLogRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private TasteProfileDeltaApplier deltaApplier;

  private final TasteProfileMapper mapper =
      new com.example.mealprep.preference.api.mapper.TasteProfileMapperImpl();
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final TasteProfileBudgetGuard budgetGuard = new TasteProfileBudgetGuard(objectMapper);
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);

  private TasteProfileServiceImpl service() {
    return new TasteProfileServiceImpl(
        tasteProfileRepository,
        versionRepository,
        auditLogRepository,
        mapper,
        eventPublisher,
        deltaApplier,
        budgetGuard,
        objectMapper,
        fixedClock);
  }

  /**
   * Build a profile sitting at {@code currentVersion} with the given optimistic version + cursor.
   */
  private static TasteProfile profileAt(
      UUID userId, int currentVersion, long optimisticVersion, String cursor) {
    TasteProfile profile = TasteProfileTestData.aggregate(userId);
    profile.setDocument(TasteProfileTestData.populatedDocument(currentVersion));
    profile.setDocumentVersion(currentVersion);
    profile.setOptimisticVersion(optimisticVersion);
    profile.setFeedbackCursor(cursor);
    profile.setBasedOnFeedbackCount(99);
    profile.setTasteVectorStatus(TasteVectorStatus.EMBEDDED);
    return profile;
  }

  /**
   * A historical snapshot at {@code targetVersion} whose document carries that internal version.
   */
  private static TasteProfileVersion snapshotAt(
      TasteProfile profile, int targetVersion, String rangeStart) {
    TasteProfileDocument doc = TasteProfileTestData.populatedDocument(targetVersion);
    return TasteProfileVersion.builder()
        .id(UUID.randomUUID())
        .tasteProfile(profile)
        .documentVersion(targetVersion)
        .documentSnapshot(doc)
        .feedbackRangeStart(rangeStart)
        .feedbackRangeEnd(rangeStart)
        .trigger(TasteProfileTrigger.BATCH)
        .deltasApplied(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode())
        .modelTierUsed("mid")
        .generatedAt(Instant.parse("2026-05-21T10:00:00Z"))
        .build();
  }

  @Test
  void rollbackTasteProfile_whenProfileMissing_throwsNotFound_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().rollbackTasteProfile(userId, 12, 0L, userId))
        .isInstanceOf(TasteProfileNotFoundException.class);

    verifyNoInteractions(versionRepository, auditLogRepository, eventPublisher);
    verify(tasteProfileRepository, never()).saveAndFlush(any());
  }

  @Test
  void rollbackTasteProfile_whenTargetVersionMissing_throwsVersionNotFound_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    TasteProfile profile = profileAt(userId, 15, 0L, "feedback-current");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 99))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().rollbackTasteProfile(userId, 99, 0L, userId))
        .isInstanceOf(TasteProfileVersionNotFoundException.class)
        .hasMessageContaining("99");

    verify(auditLogRepository, never()).save(any());
    verify(versionRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
    verify(tasteProfileRepository, never()).saveAndFlush(any());
  }

  @Test
  void rollbackTasteProfile_whenExpectedVersionStale_throws409_andWritesNothing() {
    UUID userId = UUID.randomUUID();
    TasteProfile profile = profileAt(userId, 15, 3L, "feedback-current");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

    // expectedVersion 0 != current optimistic 3 → 409. Guard fires before the version read.
    assertThatThrownBy(() -> service().rollbackTasteProfile(userId, 12, 0L, userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verifyNoInteractions(versionRepository, auditLogRepository, eventPublisher);
    verify(tasteProfileRepository, never()).saveAndFlush(any());
  }

  @Test
  void rollbackTasteProfile_happyPath_restoresAsNewMonotonicVersion_lockstep_resetsCursor() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    String targetCursor = "feedback-" + feedbackId;
    TasteProfile profile = profileAt(userId, 15, 7L, "feedback-newer");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 12))
        .thenReturn(Optional.of(snapshotAt(profile, 12, targetCursor)));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    TasteProfileDto dto = service().rollbackTasteProfile(userId, 12, 7L, userId);

    // New version is 16 (15 + 1), NOT 12; monotonic.
    assertThat(profile.getDocumentVersion()).isEqualTo(16);
    // Document internal version stamped in lock-step with the entity (16), not the target (12).
    assertThat(profile.getDocument().version()).isEqualTo(16);
    assertThat(profile.getDocument().lastUpdated())
        .isEqualTo(java.time.LocalDate.parse("2026-05-24"));
    // Cursor reset to the target version's anchor — the deterministic replay anchor.
    assertThat(profile.getFeedbackCursor()).isEqualTo(targetCursor);
    // Restored document needs re-embedding.
    assertThat(profile.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);
    assertThat(dto.documentVersion()).isEqualTo(16);
    assertThat(dto.document().version()).isEqualTo(16);
  }

  @Test
  void rollbackTasteProfile_writesRolledBackAudit_namingTheTargetVersion() {
    UUID userId = UUID.randomUUID();
    TasteProfile profile = profileAt(userId, 15, 0L, "feedback-newer");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 12))
        .thenReturn(Optional.of(snapshotAt(profile, 12, "feedback-anchor")));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().rollbackTasteProfile(userId, 12, 0L, userId);

    ArgumentCaptor<TasteProfileAuditLog> auditCaptor =
        ArgumentCaptor.forClass(TasteProfileAuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    TasteProfileAuditLog audit = auditCaptor.getValue();
    assertThat(audit.getChangeType()).isEqualTo(TasteProfileChangeType.ROLLED_BACK);
    assertThat(audit.getActorType()).isEqualTo(ActorType.USER);
    assertThat(audit.getActorUserId()).isEqualTo(userId);
    assertThat(audit.getPreviousDocumentVersion()).isEqualTo(15);
    assertThat(audit.getNewDocumentVersion()).isEqualTo(16);
    assertThat(audit.getSummary()).contains("rolled back to version 12");
  }

  @Test
  void rollbackTasteProfile_writesVersionSnapshot_withRollbackMarker_atNewVersion() {
    UUID userId = UUID.randomUUID();
    TasteProfile profile = profileAt(userId, 15, 0L, "feedback-newer");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 12))
        .thenReturn(Optional.of(snapshotAt(profile, 12, "feedback-anchor")));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().rollbackTasteProfile(userId, 12, 0L, userId);

    ArgumentCaptor<TasteProfileVersion> versionCaptor =
        ArgumentCaptor.forClass(TasteProfileVersion.class);
    verify(versionRepository).save(versionCaptor.capture());
    TasteProfileVersion snapshot = versionCaptor.getValue();
    // The rollback IS a new version, listable in version history.
    assertThat(snapshot.getDocumentVersion()).isEqualTo(16);
    assertThat(snapshot.getTrigger()).isEqualTo(TasteProfileTrigger.MANUAL);
    assertThat(snapshot.getModelTierUsed()).isEqualTo("manual");
    assertThat(snapshot.getDocumentSnapshot().version()).isEqualTo(16);
    // Synthetic ROLLBACK marker for forensic clarity.
    assertThat(snapshot.getDeltasApplied().isArray()).isTrue();
    assertThat(snapshot.getDeltasApplied()).hasSize(1);
    assertThat(snapshot.getDeltasApplied().get(0).get("op").asText()).isEqualTo("ROLLBACK");
    assertThat(snapshot.getDeltasApplied().get(0).get("fromVersion").asInt()).isEqualTo(12);
    assertThat(snapshot.getDeltasApplied().get(0).get("toVersion").asInt()).isEqualTo(16);
  }

  @Test
  void rollbackTasteProfile_publishesChangedEventAndReplayEvent_afterWrite() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    String targetCursor = "feedback-" + feedbackId;
    TasteProfile profile = profileAt(userId, 15, 0L, "feedback-newer");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 12))
        .thenReturn(Optional.of(snapshotAt(profile, 12, targetCursor)));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().rollbackTasteProfile(userId, 12, 0L, userId);

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());

    TasteProfileChangedEvent changed =
        eventCaptor.getAllValues().stream()
            .filter(TasteProfileChangedEvent.class::isInstance)
            .map(TasteProfileChangedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(changed.changeType()).isEqualTo(TasteProfileChangeType.ROLLED_BACK);
    assertThat(changed.actorType()).isEqualTo(ActorType.USER);
    assertThat(changed.documentVersion()).isEqualTo(16);
    assertThat(changed.traceId()).isEqualTo(feedbackId);

    TasteProfileRollbackReplayRequestedEvent replay =
        eventCaptor.getAllValues().stream()
            .filter(TasteProfileRollbackReplayRequestedEvent.class::isInstance)
            .map(TasteProfileRollbackReplayRequestedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(replay.userId()).isEqualTo(userId);
    assertThat(replay.restoredDocumentVersion()).isEqualTo(16);
    assertThat(replay.fromFeedbackCursor()).isEqualTo(targetCursor);
    assertThat(replay.toCursorBefore()).isEqualTo("feedback-newer");
    assertThat(replay.scopeKind()).isEqualTo("taste-profile");
    assertThat(replay.scopeId()).isEqualTo(userId);
  }

  @Test
  void rollbackTasteProfile_toCurrentVersion_writesFreshSnapshot_stillBumps() {
    UUID userId = UUID.randomUUID();
    TasteProfile profile = profileAt(userId, 15, 0L, "feedback-newer");
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    when(versionRepository.findByTasteProfileIdAndDocumentVersion(profile.getId(), 15))
        .thenReturn(Optional.of(snapshotAt(profile, 15, "feedback-self")));
    when(tasteProfileRepository.saveAndFlush(any(TasteProfile.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    TasteProfileDto dto = service().rollbackTasteProfile(userId, 15, 0L, userId);

    // Rollback to the current version still produces a fresh version (16) + audit row — every
    // rollback writes a version row (audit-consistency choice from the ticket edge-case list).
    assertThat(dto.documentVersion()).isEqualTo(16);
    verify(versionRepository).save(any(TasteProfileVersion.class));
    verify(auditLogRepository).save(any(TasteProfileAuditLog.class));
  }
}
