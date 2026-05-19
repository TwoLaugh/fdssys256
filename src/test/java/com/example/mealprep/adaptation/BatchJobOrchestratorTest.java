package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.example.mealprep.adaptation.domain.service.internal.BatchJobOrchestrator;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link BatchJobOrchestrator} — defer-on-active-sync rule + per-run cap. */
class BatchJobOrchestratorTest {

  @Test
  void processes_batch_jobs_when_no_active_sync_for_recipe() {
    AdaptationJob batchJob = job(JobPriority.BATCH, JobStatus.PENDING);

    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(batchJob));
    when(repo.findActiveByRecipeId(batchJob.getRecipeId())).thenReturn(List.of(batchJob));

    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);
    BatchJobOrchestrator orch = new BatchJobOrchestrator(repo, svc);

    orch.runBatchSweep();

    verify(svc, times(1)).processJob(batchJob);
  }

  @Test
  void defers_batch_job_when_sync_job_running_for_same_recipe() {
    UUID recipeId = UUID.randomUUID();
    AdaptationJob batchJob = job(JobPriority.BATCH, JobStatus.PENDING);
    batchJob.setRecipeId(recipeId);
    AdaptationJob syncJob = job(JobPriority.SYNC, JobStatus.RUNNING);
    syncJob.setRecipeId(recipeId);

    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(batchJob));
    when(repo.findActiveByRecipeId(recipeId)).thenReturn(List.of(batchJob, syncJob));
    when(repo.findById(batchJob.getId())).thenReturn(java.util.Optional.of(batchJob));

    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);
    BatchJobOrchestrator orch = new BatchJobOrchestrator(repo, svc);

    orch.runBatchSweep();

    // Deferred: processJob NOT called on the BATCH job; enqueuedAt is bumped via saveAndFlush.
    verify(svc, times(0)).processJob(batchJob);
    assertThat(batchJob.getEnqueuedAt()).isAfter(Instant.now().minusSeconds(5));
  }

  @Test
  void loadNextBatch_filters_out_non_batch_and_non_pending_jobs() {
    // Over-fetch returns a mix; only the BATCH+PENDING one is processable.
    AdaptationJob batchPending = job(JobPriority.BATCH, JobStatus.PENDING);
    AdaptationJob syncPending = job(JobPriority.SYNC, JobStatus.PENDING);
    AdaptationJob batchRunning = job(JobPriority.BATCH, JobStatus.RUNNING);

    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class)))
        .thenReturn(List.of(syncPending, batchRunning, batchPending));
    when(repo.findActiveByRecipeId(any())).thenReturn(List.of());

    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);
    new BatchJobOrchestrator(repo, svc).runBatchSweep();

    // Only the BATCH+PENDING job survives both filters (kills both BooleanTrue filter
    // mutants: a `return true` filter would also process the SYNC and RUNNING jobs).
    verify(svc, times(1)).processJob(batchPending);
    verify(svc, never()).processJob(syncPending);
    verify(svc, never()).processJob(batchRunning);
  }

  @Test
  void does_not_defer_when_only_other_active_job_is_terminal() {
    // hasActiveNonBatchForRecipe: the other (non-BATCH) job is SUCCEEDED — neither PENDING
    // nor RUNNING — so it must NOT count as active. A negated status conditional would
    // wrongly defer and skip processing.
    UUID recipeId = UUID.randomUUID();
    AdaptationJob batchJob = job(JobPriority.BATCH, JobStatus.PENDING);
    batchJob.setRecipeId(recipeId);
    AdaptationJob doneSync = job(JobPriority.SYNC, JobStatus.DONE);
    doneSync.setRecipeId(recipeId);

    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(batchJob));
    when(repo.findActiveByRecipeId(recipeId)).thenReturn(List.of(batchJob, doneSync));

    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);
    new BatchJobOrchestrator(repo, svc).runBatchSweep();

    verify(svc, times(1)).processJob(batchJob);
  }

  @Test
  void empty_batch_short_circuits_without_touching_service() {
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of());
    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);

    new BatchJobOrchestrator(repo, svc).runBatchSweep();

    verify(svc, never()).processJob(any());
  }

  @Test
  void runtime_exception_in_processJob_is_swallowed_and_sweep_continues() {
    AdaptationJob first = job(JobPriority.BATCH, JobStatus.PENDING);
    AdaptationJob second = job(JobPriority.BATCH, JobStatus.PENDING);
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(first, second));
    when(repo.findActiveByRecipeId(any())).thenReturn(List.of());

    AdaptationServiceImpl svc = mock(AdaptationServiceImpl.class);
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(svc).processJob(first);

    new BatchJobOrchestrator(repo, svc).runBatchSweep();

    // The throwing job must not abort the loop: the second job is still processed.
    verify(svc, times(1)).processJob(first);
    verify(svc, times(1)).processJob(second);
  }

  private static AdaptationJob job(JobPriority priority, JobStatus status) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(priority == JobPriority.BATCH ? JobSource.DATA_MODEL_CHANGE : JobSource.FEEDBACK)
        .priority(priority)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(status)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now().minusSeconds(60))
        .build();
  }
}
