package com.example.mealprep.feedback.bridge.internal;

import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily cron that prunes {@code feedback_bridge_idempotency} rows older than the retention window
 * (tickets/feedback/01g §21). The idempotency window itself is only 5 minutes; rows are retained
 * longer (default 7 days) for forensic / audit visibility, then swept.
 *
 * <p>Both the cron and the retention window are property-parameterised (matching {@code
 * PendingChangeExpirySweepScheduler}'s convention) so tests can override:
 *
 * <ul>
 *   <li>{@code mealprep.feedback.bridge.idempotency-cleanup-cron} (default {@code 0 0 4 * * ?})
 *   <li>{@code mealprep.feedback.bridge.idempotency-retention-days} (default {@code 7})
 * </ul>
 */
@Component
public class FeedbackBridgeIdempotencyCleanupScheduler {

  private static final Logger LOG =
      LoggerFactory.getLogger(FeedbackBridgeIdempotencyCleanupScheduler.class);

  private final FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private final Clock clock;
  private final long retentionDays;

  public FeedbackBridgeIdempotencyCleanupScheduler(
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      Clock clock,
      @Value("${mealprep.feedback.bridge.idempotency-retention-days:7}") long retentionDays) {
    this.idempotencyRepository = idempotencyRepository;
    this.clock = clock;
    this.retentionDays = retentionDays;
  }

  @Scheduled(cron = "${mealprep.feedback.bridge.idempotency-cleanup-cron:0 0 4 * * ?}")
  @Transactional
  public void cleanup() {
    Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
    int deleted = idempotencyRepository.deleteOlderThan(cutoff);
    if (deleted > 0) {
      LOG.info(
          "feedback-bridge idempotency cleanup deleted {} row(s) older than {} day(s)",
          deleted,
          retentionDays);
    }
  }

  /** Exposed for the mocked-Clock test to assert the cutoff math without waiting for the cron. */
  @Transactional
  public int cleanupNow() {
    Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
    return idempotencyRepository.deleteOlderThan(cutoff);
  }
}
