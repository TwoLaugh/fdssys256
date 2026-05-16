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
