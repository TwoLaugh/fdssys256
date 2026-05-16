package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async worker for {@link JobReadyEvent}. Looks up the {@code AdaptationJob} row and hands it to
 * {@code AdaptationServiceImpl.processJob}. Per LLD line 772 — the worker pipeline runs outside the
 * publishing transaction.
 *
 * <p>The listener body is NOT annotated with {@code @Transactional} — Spring Data's {@code
 * JpaRepository.findById} opens its own short tx if none exists, and {@code processJob}
 * orchestrates its own per-step transactions. Per round-7 rule, adding {@code @Transactional}
 * (REQUIRED) here would fail-fast at context load. We keep no JPA work in the listener body
 * directly: the {@code findById} call opens its own short tx.
 *
 * <p>{@code @Lazy} on the {@link AdaptationServiceImpl} dependency breaks the circular load order
 * (the impl itself publishes the event the listener consumes).
 */
@Component
public class JobReadyEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(JobReadyEventListener.class);

  private final AdaptationJobRepository jobRepository;
  private final AdaptationServiceImpl service;

  public JobReadyEventListener(
      AdaptationJobRepository jobRepository, @Lazy AdaptationServiceImpl service) {
    this.jobRepository = jobRepository;
    this.service = service;
  }

  /**
   * Process the job asynchronously. {@code AFTER_COMMIT} so the publisher's row is visible.
   * Idempotent re-entry guard: if the job's status is no longer {@code PENDING}, skip (already
   * picked up by another listener invocation or by the orphan poll).
   */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onJobReady(JobReadyEvent event) {
    AdaptationJob job = jobRepository.findById(event.jobId()).orElse(null);
    if (job == null) {
      LOG.warn("JobReadyEvent: job not found id={}", event.jobId());
      return;
    }
    if (job.getStatus() != JobStatus.PENDING) {
      LOG.info(
          "JobReadyEvent: job no longer PENDING id={} status={} — skipping",
          job.getId(),
          job.getStatus());
      return;
    }
    try {
      service.processJob(job);
    } catch (RuntimeException e) {
      LOG.warn("JobReadyEvent: processJob threw for jobId={}: {}", event.jobId(), e.toString());
    }
  }
}
