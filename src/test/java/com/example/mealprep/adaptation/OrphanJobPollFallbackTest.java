package com.example.mealprep.adaptation;

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
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEvent;
import com.example.mealprep.adaptation.domain.service.internal.OrphanJobPollFallback;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link OrphanJobPollFallback}. */
class OrphanJobPollFallbackTest {

  @Test
  void republishes_JobReadyEvent_for_old_pending_async_jobs() {
    AdaptationJob old = job(JobPriority.ASYNC, JobStatus.PENDING, Instant.now().minusSeconds(600));
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(old));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    verify(events, times(1)).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void skips_recent_pending_jobs() {
    AdaptationJob recent =
        job(JobPriority.ASYNC, JobStatus.PENDING, Instant.now().minusSeconds(60));
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(recent));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    verify(events, never()).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void skips_batch_priority_jobs() {
    AdaptationJob old = job(JobPriority.BATCH, JobStatus.PENDING, Instant.now().minusSeconds(600));
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(old));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    verify(events, never()).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void skips_old_non_pending_jobs() {
    // Old + non-BATCH but RUNNING: the status filter must exclude it. A BooleanTrue
    // mutant on `j.getStatus() == PENDING` would let this through and re-publish.
    AdaptationJob oldRunning =
        job(JobPriority.ASYNC, JobStatus.RUNNING, Instant.now().minusSeconds(600));
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(oldRunning));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    verify(events, never()).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void skips_jobs_with_null_enqueuedAt() {
    AdaptationJob noTime = job(JobPriority.ASYNC, JobStatus.PENDING, null);
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(noTime));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    verify(events, never()).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void publishes_event_carrying_the_orphan_job_id() {
    UUID jobId = UUID.randomUUID();
    AdaptationJob old = job(JobPriority.SYNC, JobStatus.PENDING, Instant.now().minusSeconds(600));
    old.setId(jobId);
    AdaptationJobRepository repo = mock(AdaptationJobRepository.class);
    when(repo.findNextPendingJobs(any(Pageable.class))).thenReturn(List.of(old));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new OrphanJobPollFallback(repo, events).poll();

    org.mockito.ArgumentCaptor<JobReadyEvent> cap =
        org.mockito.ArgumentCaptor.forClass(JobReadyEvent.class);
    verify(events).publishEvent(cap.capture());
    org.assertj.core.api.Assertions.assertThat(cap.getValue().jobId()).isEqualTo(jobId);
  }

  private static AdaptationJob job(JobPriority priority, JobStatus status, Instant enqueuedAt) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(priority == JobPriority.BATCH ? JobSource.DATA_MODEL_CHANGE : JobSource.IMPORT)
        .priority(priority)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(status)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(enqueuedAt)
        .build();
  }
}
