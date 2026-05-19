package com.example.mealprep.adaptation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEvent;
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEventListener;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link JobReadyEventListener#onJobReady} — wholly NO_COVERAGE before. Kills:
 *
 * <ul>
 *   <li>L52 {@code job == null} guard (NegateConditionals): not-found must skip processJob.
 *   <li>L56 {@code status != PENDING} re-entry guard (NegateConditionals): a RUNNING job must be
 *       skipped, a PENDING job must be processed.
 *   <li>L64 {@code service.processJob(job)} (VoidMethodCall): the happy path must invoke it; a
 *       thrown RuntimeException must be swallowed (loop-safe).
 * </ul>
 *
 * {@code AdaptationServiceImpl} is injected {@code @Lazy} as a cross-boundary worker collaborator
 * here (same pattern as {@code BatchJobOrchestratorTest}); mocking it is appropriate.
 */
class JobReadyEventListenerTest {

  private final AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
  private final AdaptationServiceImpl service = mock(AdaptationServiceImpl.class);
  private final JobReadyEventListener listener = new JobReadyEventListener(repo, service);

  private static AdaptationJob job(JobStatus status) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(JobSource.IMPORT)
        .priority(JobPriority.ASYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(status)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  @Test
  void job_not_found_skips_processing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());
    listener.onJobReady(new JobReadyEvent(id));
    verify(service, never()).processJob(any());
  }

  @Test
  void job_no_longer_pending_is_skipped() {
    AdaptationJob running = job(JobStatus.RUNNING);
    when(repo.findById(running.getId())).thenReturn(Optional.of(running));
    listener.onJobReady(new JobReadyEvent(running.getId()));
    verify(service, never()).processJob(any());
  }

  @Test
  void pending_job_is_processed() {
    AdaptationJob pending = job(JobStatus.PENDING);
    when(repo.findById(pending.getId())).thenReturn(Optional.of(pending));
    listener.onJobReady(new JobReadyEvent(pending.getId()));
    verify(service, times(1)).processJob(pending);
  }

  @Test
  void runtime_exception_from_processJob_is_swallowed() {
    AdaptationJob pending = job(JobStatus.PENDING);
    when(repo.findById(pending.getId())).thenReturn(Optional.of(pending));
    doThrow(new RuntimeException("boom")).when(service).processJob(pending);
    // Must not propagate — the async listener swallows it (LOG.warn only).
    listener.onJobReady(new JobReadyEvent(pending.getId()));
    verify(service, times(1)).processJob(pending);
  }
}
