package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.bridge.internal.FeedbackBridgeIdempotencyCleanupScheduler;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Mocked-Clock unit test for the retention sweep: rows older than the configured retention window
 * are deleted; the cutoff is {@code now - retentionDays}.
 */
class FeedbackBridgeIdempotencyCleanupSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-05-22T04:00:00Z");

  @Test
  void cleanup_deletesRowsOlderThanRetentionCutoff() {
    FeedbackBridgeIdempotencyRepository repo =
        Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(repo.deleteOlderThan(Mockito.any())).thenReturn(3);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    FeedbackBridgeIdempotencyCleanupScheduler scheduler =
        new FeedbackBridgeIdempotencyCleanupScheduler(repo, clock, 7);

    scheduler.cleanup();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(repo).deleteOlderThan(cutoff.capture());
    assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(7)));
  }

  @Test
  void cleanupNow_honoursOverriddenRetentionDays() {
    FeedbackBridgeIdempotencyRepository repo =
        Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(repo.deleteOlderThan(Mockito.any())).thenReturn(0);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    FeedbackBridgeIdempotencyCleanupScheduler scheduler =
        new FeedbackBridgeIdempotencyCleanupScheduler(repo, clock, 2);

    int deleted = scheduler.cleanupNow();

    assertThat(deleted).isZero();
    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(repo).deleteOlderThan(cutoff.capture());
    assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(2)));
  }
}
