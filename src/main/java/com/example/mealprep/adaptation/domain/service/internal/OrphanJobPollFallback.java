package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code @Scheduled} fallback poller that re-picks orphan PENDING jobs older than 5 minutes after a
 * JVM restart drops the in-memory {@code JobReadyEvent} subscriber. Per LLD line 772 and ticket
 * §step 40-43.
 *
 * <p>For each orphan, publishes a fresh {@code JobReadyEvent} — the async listener picks it up. The
 * listener's idempotency guard (status != PENDING => skip) handles the case where the original
 * event eventually fires after restart and we've already re-queued.
 */
@Component
public class OrphanJobPollFallback {

  private static final Logger LOG = LoggerFactory.getLogger(OrphanJobPollFallback.class);

  /** Re-pick PENDING jobs older than this. Per LLD line 772 ("older than 5 minutes"). */
  static final Duration ORPHAN_AGE_THRESHOLD = Duration.ofMinutes(5);

  /** Cap on the number of orphans re-queued per poll tick. */
  static final int ORPHAN_PER_RUN_CAP = 20;

  private final AdaptationJobRepository jobRepository;
  private final ApplicationEventPublisher events;

  public OrphanJobPollFallback(
      AdaptationJobRepository jobRepository, ApplicationEventPublisher events) {
    this.jobRepository = jobRepository;
    this.events = events;
  }

  @Scheduled(fixedDelay = 300_000L)
  @Transactional(readOnly = true)
  public void poll() {
    Instant cutoff = Instant.now().minus(ORPHAN_AGE_THRESHOLD);
    // Re-use findNextPendingJobs (orders SYNC/ASYNC/BATCH); filter to SYNC + ASYNC and age cutoff.
    List<AdaptationJob> orphans =
        jobRepository.findNextPendingJobs(PageRequest.of(0, ORPHAN_PER_RUN_CAP * 4)).stream()
            .filter(j -> j.getStatus() == JobStatus.PENDING)
            .filter(j -> j.getPriority() != JobPriority.BATCH)
            .filter(j -> j.getEnqueuedAt() != null && j.getEnqueuedAt().isBefore(cutoff))
            .limit(ORPHAN_PER_RUN_CAP)
            .toList();
    if (orphans.isEmpty()) {
      return;
    }
    for (AdaptationJob job : orphans) {
      LOG.info("orphan-poll re-publishing JobReadyEvent for jobId={}", job.getId());
      events.publishEvent(new JobReadyEvent(job.getId()));
    }
  }
}
