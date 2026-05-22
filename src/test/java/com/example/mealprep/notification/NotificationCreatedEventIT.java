package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.service.NotificationQueryService;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.event.NotificationCreatedEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Captures {@code NotificationCreatedEvent} and asserts it resolves via the query service. */
@SpringBootTest
@Import({TestContainersConfig.class, NotificationCreatedEventIT.CaptureConfig.class})
@ActiveProfiles("test")
class NotificationCreatedEventIT {

  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationUpdateService updateService;
  @Autowired private NotificationQueryService queryService;
  @Autowired private Capture capture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    capture.events.clear();
  }

  @Test
  void event_publishedOnce_andResolvesViaQueryService() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new ItemSpoiledEvent(
                        user, List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now())));

    assertThat(capture.events).hasSize(1);
    NotificationCreatedEvent created = capture.events.get(0);
    assertThat(created.userId()).isEqualTo(user);
    assertThat(queryService.getById(user, created.notificationId())).isPresent();
  }

  static class Capture {
    final List<NotificationCreatedEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(NotificationCreatedEvent event) {
      events.add(event);
    }
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    Capture notificationCreatedCapture() {
      return new Capture();
    }
  }
}
