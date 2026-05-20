package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-1 advisory-lock acquire for the adaptation worker pipeline.
 *
 * <p>Lives on a separate bean from {@code AdaptationServiceImpl} so the worker's call into {@link
 * #acquireLockOrFailJob} crosses a bean boundary — Spring's proxy is hit by construction, the
 * {@code @Transactional(REQUIRED)} advice fires, and {@link LockService#tryAcquire} (which requires
 * an active tx) runs against a real transaction.
 *
 * <p>Previously this method lived on {@code AdaptationServiceImpl} and was reached via an
 * {@code @Autowired @Lazy AdaptationServiceImpl self} field — a workaround for Spring's
 * self-invocation trap. Extracting the step onto a dedicated bean removes the workaround entirely.
 *
 * <p>{@code noRollbackFor = LockTimeoutException.class} so the IMPORT / DATA_MODEL_CHANGE
 * failure-excerpt write commits even when we throw on lock failure (LLD line 880).
 */
@Component
public class AdaptationLockAcquirer {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationLockAcquirer.class);

  private final LockService lockService;
  private final AdaptationJobRepository jobRepository;

  public AdaptationLockAcquirer(LockService lockService, AdaptationJobRepository jobRepository) {
    this.lockService = lockService;
    this.jobRepository = jobRepository;
  }

  /**
   * Acquire the per-recipe advisory lock for {@code job}, or fail it.
   *
   * <p>Behaviour matches the pre-extraction {@code AdaptationServiceImpl#acquireLockOrFailJob}:
   *
   * <ul>
   *   <li>IMPORT / DATA_MODEL_CHANGE: the job is bounced back to PENDING with a {@code
   *       lock-deferred} excerpt (the cron / orchestrator picks it up again later) and a {@link
   *       LockTimeoutException} is thrown to abort the current pipeline run.
   *   <li>FEEDBACK / PLAN_TIME (sync triggers): no status flip — a {@link LockTimeoutException} is
   *       thrown so the controller layer maps it to HTTP 409.
   * </ul>
   *
   * <p>The status flip for IMPORT / DATA_MODEL_CHANGE is inlined here (rather than routed back to
   * {@code AdaptationServiceImpl.transitionJobStatus}) to avoid a constructor cycle: this bean is
   * constructor-injected into {@code AdaptationServiceImpl}, so it must not depend back on it. The
   * inlined write joins the existing REQUIRED transaction, matching the pre-extraction semantics
   * (the previous {@code transitionJobStatus} call propagated REQUIRED and joined the same tx).
   */
  @Transactional(propagation = Propagation.REQUIRED, noRollbackFor = LockTimeoutException.class)
  public void acquireLockOrFailJob(AdaptationJob job) {
    boolean ok = lockService.tryAcquire(LockKey.forRecipe(job.getRecipeId()));
    if (ok) {
      return;
    }
    switch (job.getSource()) {
      case IMPORT -> {
        flipToPending(job.getId(), JobFailureReason.UNKNOWN, "lock-deferred");
        throw new LockTimeoutException("lock-deferred:IMPORT");
      }
      case DATA_MODEL_CHANGE -> {
        flipToPending(job.getId(), JobFailureReason.UNKNOWN, "lock-deferred-batch");
        throw new LockTimeoutException("lock-deferred-batch:DATA_MODEL_CHANGE");
      }
      case FEEDBACK, PLAN_TIME ->
          throw new LockTimeoutException("lock-acquire-failed:" + job.getSource());
      default -> throw new LockTimeoutException("lock-acquire-failed");
    }
  }

  /**
   * Mirror of the {@code transitionJobStatus} write path for the lock-deferred case. The job row is
   * re-read (the worker handed us a detached instance) and bounced back to {@link
   * JobStatus#PENDING} with the supplied {@code excerpt}. Excerpt is capped at 512 chars per the
   * original contract.
   */
  private void flipToPending(UUID jobId, JobFailureReason reason, String excerpt) {
    AdaptationJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null) {
      LOG.warn("acquireLockOrFailJob: job not found id={}", jobId);
      return;
    }
    job.setStatus(JobStatus.PENDING);
    if (reason != null) {
      job.setFailureReason(reason);
    }
    if (excerpt != null) {
      job.setFailureExcerpt(excerpt.length() > 512 ? excerpt.substring(0, 512) : excerpt);
    }
    jobRepository.saveAndFlush(job);
  }
}
