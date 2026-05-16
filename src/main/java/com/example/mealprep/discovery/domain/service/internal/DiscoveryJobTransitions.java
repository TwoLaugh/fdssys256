package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-step {@code @Transactional} helpers for {@link DiscoveryJobRunner}. Lives as a SEPARATE bean
 * (not on the runner itself) to avoid Spring's AOP self-invocation trap — {@code @Transactional}
 * does not apply on a same-class call through {@code this} because the proxy isn't on the call.
 * Calling {@code transitions.claim(jobId)} from the runner crosses the proxy boundary and the
 * per-method tx scope takes effect.
 *
 * <p>Per LLD lines 555-567: each runner step opens its own short tx so a partial run survives a
 * crash. The runner method itself has NO outer {@code @Transactional}.
 *
 * <p>Package-private — only the runner injects this.
 */
@Component
class DiscoveryJobTransitions {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryJobTransitions.class);

  private final DiscoveryJobRepository jobRepository;
  private final DiscoveryScrapeLogRepository scrapeLogRepository;

  DiscoveryJobTransitions(
      DiscoveryJobRepository jobRepository, DiscoveryScrapeLogRepository scrapeLogRepository) {
    this.jobRepository = jobRepository;
    this.scrapeLogRepository = scrapeLogRepository;
  }

  /**
   * Claim a {@code QUEUED} job by flipping to {@code RUNNING} + setting {@code startedAt}. Uses
   * {@code saveAndFlush} so {@link OptimisticLockingFailureException} surfaces synchronously; a
   * lost claim race returns {@link Optional#empty()}.
   *
   * <p>Per ticket invariant 5 + LLD line 521.
   */
  @Transactional
  Optional<DiscoveryJob> claim(UUID jobId) {
    DiscoveryJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null) {
      log.warn("discovery job {} not found in claim — orphaned event", jobId);
      return Optional.empty();
    }
    if (job.getStatus() != DiscoveryJobStatus.QUEUED) {
      log.info(
          "discovery job {} not QUEUED (status={}); another runner claimed it or it was cancelled",
          jobId,
          job.getStatus());
      return Optional.empty();
    }
    job.setStatus(DiscoveryJobStatus.RUNNING);
    job.setStartedAt(Instant.now());
    try {
      return Optional.of(jobRepository.saveAndFlush(job));
    } catch (OptimisticLockingFailureException ex) {
      log.info("discovery job {} claim race; another runner won", jobId);
      return Optional.empty();
    }
  }

  /** Eager write of one scrape-log row — per-fetch rows persist before the runner moves on. */
  @Transactional
  void writeScrapeRow(DiscoveryScrapeLog row) {
    scrapeLogRepository.save(row);
  }

  /**
   * Fingerprint-dedup probe used by the runner's fetch-loop step. Read-only — kept on this bean
   * (rather than reaching into the repo directly from the runner) so the runner stays in the
   * service-layer abstraction.
   */
  @Transactional(readOnly = true)
  boolean scrapeLogExistsSince(String fingerprint, Instant cutoff) {
    return scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter(fingerprint, cutoff);
  }

  /**
   * Bump {@code candidatesSeen} after the search phase produces a merged candidate list. Separate
   * helper to keep the per-step tx granularity per LLD line 560.
   */
  @Transactional
  void recordCandidatesSeen(UUID jobId, int candidatesSeen) {
    jobRepository
        .findById(jobId)
        .ifPresent(
            job -> {
              job.setCandidatesSeen(candidatesSeen);
              jobRepository.save(job);
            });
  }

  /** Bump {@code candidatesAfterFilter} after the AI-filter phase. */
  @Transactional
  void recordCandidatesAfterFilter(UUID jobId, int candidatesAfterFilter) {
    jobRepository
        .findById(jobId)
        .ifPresent(
            job -> {
              job.setCandidatesAfterFilter(candidatesAfterFilter);
              jobRepository.save(job);
            });
  }

  /**
   * Increment {@code recipesIngested} by 1 inside the same tx that wrote the SUCCESS scrape row
   * (called sequentially from the runner; the two writes are separate txs but only the success
   * scrape row is durably written first per LLD line 195).
   */
  @Transactional
  void incrementIngested(UUID jobId) {
    jobRepository
        .findById(jobId)
        .ifPresent(
            job -> {
              job.setRecipesIngested(job.getRecipesIngested() + 1);
              jobRepository.save(job);
            });
  }

  /** Increment {@code recipesSkippedDuplicate} after a DUPLICATE skip. */
  @Transactional
  void incrementSkippedDuplicate(UUID jobId) {
    jobRepository
        .findById(jobId)
        .ifPresent(
            job -> {
              job.setRecipesSkippedDuplicate(job.getRecipesSkippedDuplicate() + 1);
              jobRepository.save(job);
            });
  }

  /**
   * Terminal transition. Sets {@code status}, {@code completedAt}, {@code errorSummary}, {@code
   * sourcesSucceeded}, {@code sourcesFailed} in one tx. Returns the saved job so the caller can
   * publish the {@code DiscoveryJobCompletedEvent} with the latest snapshot.
   */
  @Transactional
  Optional<DiscoveryJob> finaliseTo(
      UUID jobId,
      DiscoveryJobStatus terminal,
      String errorSummary,
      List<String> succeeded,
      List<String> failed) {
    return jobRepository
        .findById(jobId)
        .map(
            job -> {
              job.setStatus(terminal);
              job.setCompletedAt(Instant.now());
              job.setErrorSummary(errorSummary);
              job.setSourcesSucceeded(succeeded);
              job.setSourcesFailed(failed);
              return jobRepository.saveAndFlush(job);
            });
  }
}
