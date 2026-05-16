package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code @Scheduled} cron orchestrator for BATCH-priority (data-model-change) jobs. Pulls up to 50
 * PENDING BATCH jobs per run, defers those whose recipe has an active SYNC/ASYNC job to the next
 * sweep, and processes the rest serially. Per LLD lines 718 + 788-794.
 *
 * <p>v1 runs serially (no thread-pool) per LLD line 794. Per-cron-run cap of 50 prevents one cron
 * tick from monopolising the JVM; the next tick picks up the remainder.
 */
@Component
public class BatchJobOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(BatchJobOrchestrator.class);

  /** Per-cron-run job cap; matches LLD line 794 ("v1 batch is serial"). */
  static final int BATCH_PER_RUN_CAP = 50;

  private final AdaptationJobRepository jobRepository;
  private final AdaptationServiceImpl service;

  public BatchJobOrchestrator(
      AdaptationJobRepository jobRepository, @Lazy AdaptationServiceImpl service) {
    this.jobRepository = jobRepository;
    this.service = service;
  }

  @Scheduled(cron = "${mealprep.adaptation.batch-orchestrator-cron:0 30 4 * * *}")
  public void runBatchSweep() {
    List<AdaptationJob> batch = loadNextBatch();
    if (batch.isEmpty()) {
      LOG.debug("BatchJobOrchestrator: no PENDING BATCH jobs");
      return;
    }
    int processed = 0;
    int deferred = 0;
    for (AdaptationJob job : batch) {
      if (hasActiveNonBatchForRecipe(job)) {
        deferBatch(job);
        deferred++;
        continue;
      }
      try {
        service.processJob(job);
        processed++;
      } catch (RuntimeException e) {
        LOG.warn("BatchJobOrchestrator: processJob threw for jobId={}", job.getId(), e);
      }
    }
    LOG.info(
        "BatchJobOrchestrator: processed={} deferred={} cap={}", processed, deferred, batch.size());
  }

  /**
   * PENDING BATCH-priority jobs ordered by enqueuedAt asc; capped at {@link #BATCH_PER_RUN_CAP}.
   */
  @Transactional(readOnly = true)
  protected List<AdaptationJob> loadNextBatch() {
    // Reuse the broad query then filter to BATCH; the repo's CASE-order surfaces SYNC/ASYNC first
    // so we may pull a generous over-fetch and post-filter.
    return jobRepository.findNextPendingJobs(PageRequest.of(0, BATCH_PER_RUN_CAP * 4)).stream()
        .filter(j -> j.getPriority() == JobPriority.BATCH)
        .filter(j -> j.getStatus() == JobStatus.PENDING)
        .limit(BATCH_PER_RUN_CAP)
        .toList();
  }

  private boolean hasActiveNonBatchForRecipe(AdaptationJob batchJob) {
    return jobRepository.findActiveByRecipeId(batchJob.getRecipeId()).stream()
        .anyMatch(
            other ->
                !other.getId().equals(batchJob.getId())
                    && other.getPriority() != JobPriority.BATCH
                    && (other.getStatus() == JobStatus.PENDING
                        || other.getStatus() == JobStatus.RUNNING));
  }

  /**
   * Bumps {@code enqueuedAt = now()} so the deferred BATCH job re-sorts behind everything in the
   * next sweep. Per ticket §step 37.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected void deferBatch(AdaptationJob job) {
    AdaptationJob fresh = jobRepository.findById(job.getId()).orElse(null);
    if (fresh == null || fresh.getStatus() != JobStatus.PENDING) {
      return;
    }
    fresh.setEnqueuedAt(Instant.now());
    jobRepository.saveAndFlush(fresh);
  }
}
