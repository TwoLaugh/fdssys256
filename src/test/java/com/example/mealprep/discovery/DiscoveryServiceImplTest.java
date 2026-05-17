package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.api.mapper.DiscoveryJobMapper;
import com.example.mealprep.discovery.api.mapper.DiscoveryScrapeLogMapper;
import com.example.mealprep.discovery.api.mapper.DiscoverySourceMapper;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.internal.DiscoveryJobRunner;
import com.example.mealprep.discovery.domain.service.internal.DiscoveryServiceImpl;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobAlreadyTerminalException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests over the service implementation. Persistence + event publication are mocked; the focus
 * is the branching/decisions.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryServiceImplTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private DiscoveryScrapeLogRepository scrapeLogRepository;
  @Mock private DiscoveryJobMapper jobMapper;
  @Mock private DiscoverySourceMapper sourceMapper;
  @Mock private DiscoveryScrapeLogMapper scrapeLogMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DiscoveryJobRunner runner;

  private DiscoveryServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new DiscoveryServiceImpl(
            jobRepository,
            sourceRepository,
            scrapeLogRepository,
            jobMapper,
            sourceMapper,
            scrapeLogMapper,
            eventPublisher,
            new ObjectMapper(),
            runner);
  }

  // ---------- startJob ----------

  @Test
  void startJob_nullSourceKeys_resolvesAllEnabledSources_persistsAndPublishes() {
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    DiscoverySource b = DiscoveryTestData.sampleSource("src_b");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a, b));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    DiscoveryJobDto result = service.startJob(userId, req);

    assertThat(result.status()).isEqualTo(DiscoveryJobStatus.QUEUED);
    verify(eventPublisher).publishEvent(any(DiscoveryJobStartedEvent.class));
    verify(jobRepository).saveAndFlush(any(DiscoveryJob.class));
  }

  @Test
  void startJob_unknownSourceKey_throws422() {
    UUID userId = UUID.randomUUID();
    when(sourceRepository.findBySourceKeyIn(anyList())).thenReturn(List.of());

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_unknown"),
            null);

    assertThatThrownBy(() -> service.startJob(userId, req))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("unknown or disabled source keys");
    verify(jobRepository, never()).saveAndFlush(any(DiscoveryJob.class));
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void startJob_disabledRequestedSource_throws422() {
    UUID userId = UUID.randomUUID();
    DiscoverySource disabled = DiscoveryTestData.sampleSource("src_disabled");
    disabled.setEnabled(false);
    when(sourceRepository.findBySourceKeyIn(anyList())).thenReturn(List.of(disabled));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_disabled"),
            null);

    assertThatThrownBy(() -> service.startJob(userId, req))
        .isInstanceOf(DiscoveryConstraintInvalidException.class);
  }

  @Test
  void startJob_zeroEnabledSources_throws422() {
    UUID userId = UUID.randomUUID();
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    assertThatThrownBy(() -> service.startJob(userId, req))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("zero enabled sources");
  }

  // ---------- runJobSync ----------

  @Test
  void runJobSync_throwsUnsupportedOperation() {
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    assertThatThrownBy(() -> service.runJobSync(UUID.randomUUID(), req, Duration.ofSeconds(10)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("discovery-01f");
  }

  // ---------- cancelJob ----------

  @Test
  void cancelJob_queued_flipsToFailed() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.QUEUED);
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));
    // The QUEUED branch now flips via native UPDATE (round-8 retro: avoids @Version race with the
    // async runner). Stub the rowcount = 1 and verify the call instead of asserting in-memory
    // entity state (the service no longer mutates the loaded entity).
    when(jobRepository.markCancelled(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.eq(DiscoveryJobStatus.FAILED),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("cancelled by user")))
        .thenReturn(1);

    service.cancelJob(userId, jobId);

    verify(jobRepository)
        .markCancelled(
            org.mockito.ArgumentMatchers.eq(jobId),
            org.mockito.ArgumentMatchers.eq(DiscoveryJobStatus.FAILED),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("cancelled by user"));
    verify(runner).requestCancellation(jobId);
  }

  @Test
  void cancelJob_unknownJob_throws404() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.cancelJob(userId, jobId))
        .isInstanceOf(DiscoveryJobNotFoundException.class);
  }

  @Test
  void cancelJob_terminalStateFailed_throws422() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.FAILED);
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));
    assertThatThrownBy(() -> service.cancelJob(userId, jobId))
        .isInstanceOf(DiscoveryJobAlreadyTerminalException.class);
  }

  @Test
  void cancelJob_runningState_setsCancellationFlag_doesNotMutateRow() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.RUNNING);
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));

    service.cancelJob(userId, jobId);

    verify(runner).requestCancellation(jobId);
    // 01d: the row stays RUNNING; the runner transitions it on next iteration.
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.RUNNING);
    verify(jobRepository, never()).saveAndFlush(any(DiscoveryJob.class));
  }

  // ---------- enable/disable ----------

  @Test
  void enableSource_flipsEnabledTrue_clearsUserDisabled() {
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    src.setEnabled(false);
    src.setUserDisabled(true);
    when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.of(src));
    when(sourceRepository.saveAndFlush(any(DiscoverySource.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(sourceMapper.toDto(any(DiscoverySource.class))).thenReturn(stubSourceDto(true));

    service.enableSource("src_a");

    assertThat(src.isEnabled()).isTrue();
    assertThat(src.isUserDisabled()).isFalse();
  }

  @Test
  void disableSource_flipsEnabledFalse_doesNotTouchUserDisabled() {
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    src.setEnabled(true);
    src.setUserDisabled(false);
    when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.of(src));
    when(sourceRepository.saveAndFlush(any(DiscoverySource.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(sourceMapper.toDto(any(DiscoverySource.class))).thenReturn(stubSourceDto(false));

    service.disableSource("src_a");

    assertThat(src.isEnabled()).isFalse();
    assertThat(src.isUserDisabled()).isFalse();
  }

  @Test
  void enableSource_unknownKey_throws404() {
    when(sourceRepository.findBySourceKey("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.enableSource("nope"))
        .isInstanceOf(DiscoverySourceNotFoundException.class);
  }

  // ---------- runOrphanSweep ----------

  @Test
  void runOrphanSweep_delegatesToRunner() {
    when(runner.sweepOrphansNow()).thenReturn(new OrphanSweepResultDto(3));
    assertThat(service.runOrphanSweep().resumedCount()).isEqualTo(3);
    verify(runner).sweepOrphansNow();
  }

  // ---------- helpers ----------

  private DiscoveryJobDto stubJobDto(UUID userId, DiscoveryJobStatus status) {
    return new DiscoveryJobDto(
        UUID.randomUUID(),
        userId,
        DiscoveryJobTrigger.COLD_START,
        5,
        DiscoveryTestData.sampleConstraints(),
        List.of("src_a", "src_b"),
        status,
        Instant.now(),
        null,
        null,
        0,
        0,
        0,
        0,
        List.of(),
        List.of(),
        null,
        UUID.randomUUID(),
        0L);
  }

  private DiscoverySourceDto stubSourceDto(boolean enabled) {
    return new DiscoverySourceDto(
        UUID.randomUUID(),
        "src_a",
        "Sample",
        DiscoverySourceKind.SITEMAP,
        "https://example.test",
        enabled,
        6,
        500,
        true,
        "MealPrepAI/1.0",
        0,
        null,
        null,
        null,
        0L);
  }
}
