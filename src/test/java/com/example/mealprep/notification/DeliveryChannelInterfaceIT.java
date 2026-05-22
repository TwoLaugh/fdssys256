package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.entity.DeliveryOutcome;
import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.domain.service.internal.delivery.DeliveryChannel;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the {@code DeliveryChannel} SPI seam: a second channel bean receives delivery too. */
@SpringBootTest
@Import({TestContainersConfig.class, DeliveryChannelInterfaceIT.SecondChannelConfig.class})
@ActiveProfiles("test")
class DeliveryChannelInterfaceIT {

  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationUpdateService updateService;
  @Autowired private RecordingChannel recordingChannel;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    recordingChannel.count.set(0);
  }

  @Test
  void secondChannel_receivesDeliveryAlongsideInApp() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new ItemSpoiledEvent(
                        user, List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now())));

    // Both channels accept → two delivery-log rows (IN_APP + PUSH) for the one notification.
    Long logs =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_delivery_log WHERE outcome = 'DELIVERED'",
            Long.class);
    assertThat(logs).isEqualTo(2L);
    assertThat(recordingChannel.count.get()).isEqualTo(1);
  }

  static class RecordingChannel implements DeliveryChannel {
    final AtomicInteger count = new AtomicInteger();

    @Override
    public Channel channel() {
      return Channel.PUSH;
    }

    @Override
    public boolean accepts(Notification notification) {
      return true;
    }

    @Override
    public DeliveryOutcome deliver(Notification notification) {
      count.incrementAndGet();
      return DeliveryOutcome.DELIVERED;
    }
  }

  @TestConfiguration
  static class SecondChannelConfig {
    @Bean
    RecordingChannel recordingChannel() {
      return new RecordingChannel();
    }
  }
}
