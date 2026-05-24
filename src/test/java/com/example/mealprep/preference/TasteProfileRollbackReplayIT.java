package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.event.TasteProfileRollbackReplayRequestedEvent;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Testcontainers IT for the taste-profile rollback path (preference-01h). Exercises the real Spring
 * + Postgres stack at the service layer: revert restores the prior document as a new monotonic
 * version, resets the cursor, writes the ROLLED_BACK audit + version rows, and publishes both the
 * {@link TasteProfileChangedEvent}(ROLLED_BACK) and {@link
 * TasteProfileRollbackReplayRequestedEvent} AFTER_COMMIT.
 *
 * <p>The replay re-derivation itself (the 01g orchestrator re-run on the published event) is
 * covered by the feedback module's listener unit test + its own ITs; here we assert the delegation
 * event is published AFTER_COMMIT with the correct from-cursor. The capturer is a pure in-memory
 * spy with no {@code @Transactional} (which would CGLIB-proxy the bean and make the test's direct
 * field reads hit the proxy's uninitialised null lists); the real REQUIRES_NEW write-path contract
 * lives on the production {@code TasteProfileRollbackReplayListener}.
 *
 * <p>DB-state is asserted via repository reads on the test thread (the document is an eager JSONB
 * column, so no lazy-collection access escapes a closed session). Cleanup deletes children
 * (audit/version FK→profile) before the parent profile.
 */
@SpringBootTest
@Import({TestContainersConfig.class, TasteProfileRollbackReplayIT.RollbackEventCapturer.class})
@ActiveProfiles("test")
class TasteProfileRollbackReplayIT {

  @Autowired private TasteProfileUpdateService updateService;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;
  @Autowired private RollbackEventCapturer capturer;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    tasteProfileRepository.deleteAll();
    capturer.clear();
  }

  @Test
  void rollback_restoresPriorDocumentAsNewMonotonicVersion_resetsCursor_flipsVectorPending() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId); // v1 (empty doc)

    // v2 — a populated manual override (the document we will roll BACK over).
    UpdateTasteProfileRequest override = TasteProfileTestData.updateRequest(0L);
    updateService.applyManualOverride(userId, override, userId);

    long optimisticBeforeRollback = currentOptimisticVersion(userId);

    // Roll back to v1 (the empty initial document).
    TasteProfileDto rolledBack =
        updateService.rollbackTasteProfile(userId, 1, optimisticBeforeRollback, userId);

    // New version is v3 (monotonic), NOT 1.
    assertThat(rolledBack.documentVersion()).isEqualTo(3);
    assertThat(rolledBack.document().version()).isEqualTo(3);

    TasteProfile reread = tasteProfileRepository.findByUserId(userId).orElseThrow();
    assertThat(reread.getDocumentVersion()).isEqualTo(3);
    assertThat(reread.getDocument().version()).isEqualTo(3);
    // Document content equals v1's empty snapshot — no flavour likes (the v2 override had some).
    TasteProfileDocument doc = reread.getDocument();
    assertThat(doc.flavourPreferences().likes()).isEmpty();
    assertThat(doc.softConstraints().intolerances()).isEmpty();
    // Restored document needs re-embedding.
    assertThat(reread.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);
    // basedOnFeedbackCount restored to v1's value (0).
    assertThat(reread.getBasedOnFeedbackCount()).isZero();
  }

  @Test
  void rollback_writesRolledBackAuditAndVersionRows_andPublishesBothEventsAfterCommit() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId); // v1
    updateService.applyManualOverride(userId, TasteProfileTestData.updateRequest(0L), userId); // v2
    capturer.clear();

    long optimistic = currentOptimisticVersion(userId);
    updateService.rollbackTasteProfile(userId, 1, optimistic, userId); // v3

    // Audit rows: INITIALIZED + MANUAL_OVERRIDE + ROLLED_BACK = 3.
    assertThat(auditLogRepository.count()).isEqualTo(3L);
    // Version snapshots: v1 + v2 + v3(rollback) = 3.
    assertThat(versionRepository.count()).isEqualTo(3L);

    // The rollback ChangedEvent (ROLLED_BACK) fired exactly once AFTER_COMMIT.
    assertThat(capturer.changedEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.changeType()).isEqualTo(TasteProfileChangeType.ROLLED_BACK);
              assertThat(ev.documentVersion()).isEqualTo(3);
              assertThat(ev.userId()).isEqualTo(userId);
            });

    // The replay-delegation event fired exactly once AFTER_COMMIT, carrying the restored version.
    assertThat(capturer.replayEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.restoredDocumentVersion()).isEqualTo(3);
              assertThat(ev.scopeKind()).isEqualTo("taste-profile");
              assertThat(ev.scopeId()).isEqualTo(userId);
            });
  }

  @Test
  void rollback_returns404_whenTargetVersionMissing() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    long optimistic = currentOptimisticVersion(userId);

    assertThatThrownBy(() -> updateService.rollbackTasteProfile(userId, 99, optimistic, userId))
        .isInstanceOf(
            com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException.class);

    // No new rows from the failed rollback (only the init audit + version remain).
    assertThat(auditLogRepository.count()).isEqualTo(1L);
    assertThat(versionRepository.count()).isEqualTo(1L);
  }

  @Test
  void rollback_returns409_onStaleExpectedVersion_noRowsWritten() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    capturer.clear();

    // expectedVersion 999 won't match the current optimistic version.
    assertThatThrownBy(() -> updateService.rollbackTasteProfile(userId, 1, 999L, userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(auditLogRepository.count()).isEqualTo(1L);
    assertThat(versionRepository.count()).isEqualTo(1L);
    assertThat(capturer.changedEvents).isEmpty();
    assertThat(capturer.replayEvents).isEmpty();
  }

  private long currentOptimisticVersion(UUID userId) {
    return tasteProfileRepository.findByUserId(userId).orElseThrow().getOptimisticVersion();
  }

  /**
   * Spring-managed AFTER_COMMIT capturer. The replay listener uses {@code REQUIRES_NEW} so its
   * write commits despite firing AFTER_COMMIT; this capturer mirrors that boundary on its (no-op)
   * read so the test exercises the same transaction-propagation contract the production listener
   * relies on.
   */
  @Component
  static class RollbackEventCapturer {
    final List<TasteProfileChangedEvent> changedEvents = new CopyOnWriteArrayList<>();
    final List<TasteProfileRollbackReplayRequestedEvent> replayEvents =
        new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChanged(TasteProfileChangedEvent ev) {
      changedEvents.add(ev);
    }

    // No @Transactional here: this capturer is a pure in-memory test spy. Adding @Transactional
    // makes Spring CGLIB-proxy the bean, so the test's direct field reads (capturer.changedEvents /
    // .replayEvents) would hit the proxy's uninitialised null fields instead of the target's lists.
    // The production listener (TasteProfileRollbackReplayListener) carries the real REQUIRES_NEW
    // transaction contract; this spy only needs to record the AFTER_COMMIT event.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onReplay(TasteProfileRollbackReplayRequestedEvent ev) {
      replayEvents.add(ev);
    }

    void clear() {
      changedEvents.clear();
      replayEvents.clear();
    }
  }
}
