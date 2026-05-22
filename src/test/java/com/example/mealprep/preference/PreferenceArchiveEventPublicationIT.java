package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.repository.PreferenceArchiveRepository;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.event.PreferenceArchivedEvent;
import com.example.mealprep.preference.event.PreferenceRePromotedEvent;
import com.example.mealprep.preference.testdata.PreferenceArchiveTestData;
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
 * Verifies the AFTER_COMMIT semantics of {@link PreferenceArchivedEvent} and {@link
 * PreferenceRePromotedEvent}. The listener is a {@code @Component} so it's discovered by the Spring
 * Boot test context.
 */
@SpringBootTest
@Import({TestContainersConfig.class, PreferenceArchiveEventPublicationIT.EventCapturer.class})
@ActiveProfiles("test")
class PreferenceArchiveEventPublicationIT {

  @Autowired private PreferenceArchiveUpdateService updateService;
  @Autowired private EventCapturer capturer;
  @Autowired private PreferenceArchiveRepository archiveRepository;

  @AfterEach
  void cleanup() {
    archiveRepository.deleteAll();
    capturer.clear();
  }

  @Test
  void archiveItem_publishesArchivedEvent_afterCommit() {
    UUID userId = UUID.randomUUID();
    updateService.archiveItem(userId, PreferenceArchiveTestData.archiveRequest());

    assertThat(capturer.archivedEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.fieldPath()).isEqualTo(PreferenceArchiveTestData.DEFAULT_FIELD_PATH);
              assertThat(ev.itemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
              assertThat(ev.reason()).isEqualTo(ArchiveReason.LOW_EVIDENCE);
              assertThat(ev.scopeKind()).isEqualTo("taste-profile-archive");
              assertThat(ev.scopeId()).isEqualTo(userId);
            });
    assertThat(capturer.rePromotedEvents).isEmpty();
  }

  @Test
  void markRePromoted_publishesRePromotedEvent_afterCommit() {
    UUID userId = UUID.randomUUID();
    updateService.archiveItem(userId, PreferenceArchiveTestData.archiveRequest());
    capturer.clear();

    updateService.markRePromoted(
        userId,
        PreferenceArchiveTestData.DEFAULT_FIELD_PATH,
        PreferenceArchiveTestData.DEFAULT_ITEM_KEY);

    assertThat(capturer.rePromotedEvents)
        .singleElement()
        .satisfies(
            ev -> {
              assertThat(ev.userId()).isEqualTo(userId);
              assertThat(ev.fieldPath()).isEqualTo(PreferenceArchiveTestData.DEFAULT_FIELD_PATH);
              assertThat(ev.itemKey()).isEqualTo(PreferenceArchiveTestData.DEFAULT_ITEM_KEY);
              assertThat(ev.scopeKind()).isEqualTo("taste-profile-archive");
            });
    assertThat(capturer.archivedEvents).isEmpty();
  }

  /**
   * Spring-managed listener capturing both event types AFTER_COMMIT (only on successful commit).
   */
  @Component
  static class EventCapturer {
    final List<PreferenceArchivedEvent> archivedEvents = new CopyOnWriteArrayList<>();
    final List<PreferenceRePromotedEvent> rePromotedEvents = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onArchived(PreferenceArchivedEvent ev) {
      archivedEvents.add(ev);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRePromoted(PreferenceRePromotedEvent ev) {
      rePromotedEvents.add(ev);
    }

    void clear() {
      archivedEvents.clear();
      rePromotedEvents.clear();
    }
  }
}
