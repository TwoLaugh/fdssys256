package com.example.mealprep.adaptation.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit coverage of {@link AdaptationLockAcquirer} and proof that the {@code @Transactional} advice
 * fires by bean-boundary construction (not by lazy self-injection).
 *
 * <p>Previously this step lived on {@code AdaptationServiceImpl.acquireLockOrFailJob} and was
 * reached via an {@code @Autowired @Lazy AdaptationServiceImpl self} field — a Spring
 * self-invocation workaround. Extracting it onto a dedicated bean removes the workaround entirely;
 * this test pins the contract.
 */
@ExtendWith(MockitoExtension.class)
class AdaptationLockAcquirerTest {

  @Mock private LockService lockService;
  @Mock private AdaptationJobRepository jobRepository;

  private AdaptationLockAcquirer acquirer;

  @BeforeEach
  void setUp() {
    acquirer = new AdaptationLockAcquirer(lockService, jobRepository);
  }

  // ---------- happy path ----------

  @Test
  void acquireLockOrFailJob_lockAcquired_returns_doesNotTouchRepository() {
    AdaptationJob job = job(JobSource.IMPORT);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(true);

    acquirer.acquireLockOrFailJob(job);

    verify(jobRepository, never()).findById(any(UUID.class));
    verify(jobRepository, never()).saveAndFlush(any(AdaptationJob.class));
  }

  // ---------- IMPORT / DATA_MODEL_CHANGE: lock-deferred (flips job back to PENDING) ----------

  @Test
  void acquireLockOrFailJob_import_lockBusy_flipsToPending_andThrowsLockDeferred() {
    AdaptationJob job = job(JobSource.IMPORT);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);
    when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    assertThatThrownBy(() -> acquirer.acquireLockOrFailJob(job))
        .isInstanceOf(LockTimeoutException.class)
        .hasMessageContaining("lock-deferred:IMPORT");

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(jobRepository, times(1)).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(saved.getValue().getFailureReason()).isEqualTo(JobFailureReason.UNKNOWN);
    assertThat(saved.getValue().getFailureExcerpt()).isEqualTo("lock-deferred");
  }

  @Test
  void acquireLockOrFailJob_dataModelChange_lockBusy_flipsToPending_andThrowsLockDeferredBatch() {
    AdaptationJob job = job(JobSource.DATA_MODEL_CHANGE);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);
    when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    assertThatThrownBy(() -> acquirer.acquireLockOrFailJob(job))
        .isInstanceOf(LockTimeoutException.class)
        .hasMessageContaining("lock-deferred-batch:DATA_MODEL_CHANGE");

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(jobRepository, times(1)).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(saved.getValue().getFailureExcerpt()).isEqualTo("lock-deferred-batch");
  }

  @Test
  void acquireLockOrFailJob_import_lockBusy_jobMissing_logsAndThrowsAnyway() {
    // The job-row re-read returns empty (deleted out from under us); the LOG.warn path runs but
    // the lock-deferred exception MUST still propagate to abort the worker pipeline.
    AdaptationJob job = job(JobSource.IMPORT);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);
    when(jobRepository.findById(job.getId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> acquirer.acquireLockOrFailJob(job))
        .isInstanceOf(LockTimeoutException.class)
        .hasMessageContaining("lock-deferred:IMPORT");
    verify(jobRepository, never()).saveAndFlush(any(AdaptationJob.class));
  }

  // ---------- FEEDBACK / PLAN_TIME (sync triggers): no flip, just throw ----------

  @Test
  void acquireLockOrFailJob_feedback_lockBusy_throwsImmediately_withoutDbWrite() {
    AdaptationJob job = job(JobSource.FEEDBACK);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);

    assertThatThrownBy(() -> acquirer.acquireLockOrFailJob(job))
        .isInstanceOf(LockTimeoutException.class)
        .hasMessageContaining("lock-acquire-failed:FEEDBACK");

    verify(jobRepository, never()).findById(any(UUID.class));
    verify(jobRepository, never()).saveAndFlush(any(AdaptationJob.class));
  }

  @Test
  void acquireLockOrFailJob_planTime_lockBusy_throwsImmediately_withoutDbWrite() {
    AdaptationJob job = job(JobSource.PLAN_TIME);
    when(lockService.tryAcquire(any(LockKey.class))).thenReturn(false);

    assertThatThrownBy(() -> acquirer.acquireLockOrFailJob(job))
        .isInstanceOf(LockTimeoutException.class)
        .hasMessageContaining("lock-acquire-failed:PLAN_TIME");

    verify(jobRepository, never()).findById(any(UUID.class));
    verify(jobRepository, never()).saveAndFlush(any(AdaptationJob.class));
  }

  // ---------- @Transactional contract on the new bean ----------

  @Test
  void acquireLockOrFailJob_isAnnotatedTransactional_soProxyAdviceFires()
      throws NoSuchMethodException {
    // The cross-cutting concern that was previously routed via @Lazy self is just
    // @Transactional(REQUIRED, noRollbackFor = LockTimeoutException.class). Pinning the
    // annotation contract here means a future edit that strips it surfaces immediately —
    // a regression to the silent IllegalTransactionStateException is impossible.
    Method m = AdaptationLockAcquirer.class.getMethod("acquireLockOrFailJob", AdaptationJob.class);
    Transactional tx = m.getAnnotation(Transactional.class);
    assertThat(tx)
        .as("acquireLockOrFailJob must be @Transactional so lockService.tryAcquire has a tx")
        .isNotNull();
    assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
    assertThat(tx.noRollbackFor()).contains(LockTimeoutException.class);
  }

  // ---------- helpers ----------

  private static AdaptationJob job(JobSource source) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }
}
