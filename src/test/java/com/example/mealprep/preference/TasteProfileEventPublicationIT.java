package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.event.TasteProfileRefreshRequestedEvent;
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
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Verifies the AFTER_COMMIT semantics of {@link TasteProfileChangedEvent} and {@link
 * TasteProfileRefreshRequestedEvent}. The listener is a {@code @Component} so it's discovered by
 * the Spring Boot test context.
 */
@SpringBootTest
@Import({TestContainersConfig.class, TasteProfileEventPublicationIT.EventCapturer.class})
@ActiveProfiles("test")
class TasteProfileEventPublicationIT {

  @Autowired private TasteProfileUpdateService updateService;
  @Autowired private EventCapturer capturer;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;

  @AfterEach
  void cleanup() {
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    tasteProfileRepository.deleteAll();
    capturer.clear();
  }

  @Test
  void initialise_publishesChangedEvent_afterCommit() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);

    assertThat(capturer.changedEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.changeType()).isEqualTo(TasteProfileChangeType.INITIALIZED);
              assertThat(ev.scopeKind()).isEqualTo("taste-profile");
              assertThat(ev.scopeId()).isEqualTo(userId);
              assertThat(ev.documentVersion()).isEqualTo(1);
            });
    assertThat(capturer.refreshEvents).isEmpty();
  }

  @Test
  void manualOverride_publishesChangedEvent_afterCommit() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    capturer.clear();

    UpdateTasteProfileRequest request = TasteProfileTestData.updateRequest(0L);
    updateService.applyManualOverride(userId, request, userId);

    assertThat(capturer.changedEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.changeType()).isEqualTo(TasteProfileChangeType.MANUAL_OVERRIDE);
              assertThat(ev.documentVersion()).isEqualTo(2);
            });
  }

  @Test
  void triggerRefresh_publishesRefreshRequestedEvent_butNotChangedEvent() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(userId);
    capturer.clear();

    UUID traceId = UUID.randomUUID();
    updateService.triggerRefresh(
        userId, new TriggerTasteProfileRefreshRequest("f-1", "f-99"), userId, traceId);

    assertThat(capturer.refreshEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.feedbackRangeStart()).isEqualTo("f-1");
              assertThat(ev.feedbackRangeEnd()).isEqualTo("f-99");
              assertThat(ev.traceId()).isEqualTo(traceId);
            });
    assertThat(capturer.changedEvents).isEmpty();
  }

  /**
   * Spring-managed listener capturing both event types AFTER_COMMIT (i.e. only on successful
   * commit).
   */
  @Component
  static class EventCapturer {
    final List<TasteProfileChangedEvent> changedEvents = new CopyOnWriteArrayList<>();
    final List<TasteProfileRefreshRequestedEvent> refreshEvents = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChanged(TasteProfileChangedEvent ev) {
      changedEvents.add(ev);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRefresh(TasteProfileRefreshRequestedEvent ev) {
      refreshEvents.add(ev);
    }

    void clear() {
      changedEvents.clear();
      refreshEvents.clear();
    }
  }
}
