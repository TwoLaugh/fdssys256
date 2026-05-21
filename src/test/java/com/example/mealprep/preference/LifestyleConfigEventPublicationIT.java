package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.domain.repository.LifestyleConfigAuditLogRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.event.LifestyleConfigChangedEvent;
import com.example.mealprep.preference.event.LifestyleConfigInitialisedEvent;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Proves {@link LifestyleConfigInitialisedEvent} fires exactly once on {@code initialise} and
 * {@link LifestyleConfigChangedEvent} fires exactly once {@code AFTER_COMMIT} on a successful PUT
 * that mutated a section. Capture listeners are {@code @Transactional(REQUIRES_NEW)} per the
 * adaptation EventPublicationIT precedent.
 */
@SpringBootTest
@Import({TestContainersConfig.class, LifestyleConfigEventPublicationIT.EventCaptureConfig.class})
@ActiveProfiles("test")
class LifestyleConfigEventPublicationIT {

  @Autowired private LifestyleConfigUpdateService updateService;
  @Autowired private LifestyleConfigRepository repository;
  @Autowired private LifestyleConfigAuditLogRepository auditRepository;
  @Autowired private ChangedEventCapture changedCapture;
  @Autowired private InitialisedEventCapture initCapture;

  @AfterEach
  void cleanup() {
    auditRepository.deleteAll();
    repository.deleteAll();
    changedCapture.captured().clear();
    initCapture.captured().clear();
  }

  @Test
  void initialise_publishesInitialisedEventOnce() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(
        userId, LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));

    assertThat(initCapture.captured()).hasSize(1);
    assertThat(initCapture.captured().get(0).userId()).isEqualTo(userId);
    assertThat(changedCapture.captured()).isEmpty();
  }

  @Test
  void update_thatMutatesPantryTracking_publishesChangedEventOnceWithCorrectSection() {
    UUID userId = UUID.randomUUID();
    updateService.initialise(
        userId, LifestyleConfigTestData.updateRequest(LifestyleConfigTestData.fullDocument(), 0L));
    updateService.update(
        userId,
        LifestyleConfigTestData.updateRequest(
            LifestyleConfigTestData.fullDocumentWithPantryDisabled(), 0L),
        userId);

    assertThat(changedCapture.captured()).hasSize(1);
    LifestyleConfigChangedEvent e = changedCapture.captured().get(0);
    assertThat(e.userId()).isEqualTo(userId);
    assertThat(e.changedSections()).containsExactly("pantryTracking");
    assertThat(e.scopeKind()).isEqualTo("lifestyle-config");
    assertThat(e.scopeId()).isEqualTo(userId);
  }

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    ChangedEventCapture changedEventCapture() {
      return new ChangedEventCapture();
    }

    @Bean
    InitialisedEventCapture initialisedEventCapture() {
      return new InitialisedEventCapture();
    }
  }

  static class ChangedEventCapture {
    private final List<LifestyleConfigChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(LifestyleConfigChangedEvent event) {
      events.add(event);
    }

    List<LifestyleConfigChangedEvent> captured() {
      return events;
    }
  }

  static class InitialisedEventCapture {
    private final List<LifestyleConfigInitialisedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(LifestyleConfigInitialisedEvent event) {
      events.add(event);
    }

    List<LifestyleConfigInitialisedEvent> captured() {
      return events;
    }
  }
}
