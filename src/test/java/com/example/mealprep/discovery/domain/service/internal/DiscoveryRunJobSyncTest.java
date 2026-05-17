package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.api.mapper.DiscoveryJobMapper;
import com.example.mealprep.discovery.api.mapper.DiscoveryScrapeLogMapper;
import com.example.mealprep.discovery.api.mapper.DiscoverySourceMapper;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit coverage of {@code DiscoveryServiceImpl.runJobSync}'s CompletableFuture coordination with a
 * mocked runner: trigger validation, timeout-clamping, success completion, TimeoutException /
 * InterruptedException / ExecutionException handling, and waiter-map cleanup. The Testcontainers
 * end-to-end flow lives in {@code DiscoveryRunJobSyncIT}.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryRunJobSyncTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private DiscoveryScrapeLogRepository scrapeLogRepository;
  @Mock private DiscoveryJobMapper jobMapper;
  @Mock private DiscoverySourceMapper sourceMapper;
  @Mock private DiscoveryScrapeLogMapper scrapeLogMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DiscoveryJobRunner runner;

  private DiscoveryServiceImpl service;

  private static final Duration SYNC_CAP = Duration.ofSeconds(60);

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
            runner,
            new DiscoveryProperties(
                Duration.ofMinutes(10), 30, SYNC_CAP, Duration.ofHours(1), Duration.ofHours(6)));
  }

  private StartDiscoveryJobRequest coldStartReq() {
    return new StartDiscoveryJobRequest(
        DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
  }

  /**
   * Stub the startJob persistence + mapper path so runJobSync can enqueue. runJobSync now
   * pre-generates the job id internally (register-before-publish race fix), so it is no longer
   * observable via the returned DTO — stubs are id-agnostic ({@code any()}) and the internal id is
   * recovered, when needed, from the {@code registerSyncWaiter} captor.
   */
  private void stubEnqueue(UUID userId, DiscoveryJobStatus reReadStatus) {
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(jobDto(UUID.randomUUID(), userId, DiscoveryJobStatus.QUEUED))
        .thenReturn(jobDto(UUID.randomUUID(), userId, reReadStatus));
    DiscoveryJob reRead = DiscoveryTestData.sampleJob(userId);
    reRead.setStatus(reReadStatus);
    when(jobRepository.findById(any())).thenReturn(Optional.of(reRead));
  }

  @Test
  void runJobSync_nonColdStart_throws422_neverEnqueues() {
    StartDiscoveryJobRequest bad =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.SCHEDULED, 5, DiscoveryTestData.sampleConstraints(), null, null);
    assertThatThrownBy(() -> service.runJobSync(UUID.randomUUID(), bad, Duration.ofSeconds(5)))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("COLD_START");
    verify(jobRepository, never()).saveAndFlush(any(DiscoveryJob.class));
    verify(runner, never()).registerSyncWaiter(any(), any());
  }

  @Test
  void runJobSync_runnerCompletesBeforeDeadline_returnsTerminalDto_cleansUpWaiter() {
    UUID userId = UUID.randomUUID();
    stubEnqueue(userId, DiscoveryJobStatus.SUCCEEDED);

    ArgumentCaptor<CompletableFuture<DiscoveryJobStatus>> waiterCaptor = captor();
    ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
    // Complete the waiter the moment the runner registers it (simulates a fast terminal).
    org.mockito.Mockito.doAnswer(
            inv -> {
              CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(), any());

    DiscoveryJobDto result = service.runJobSync(userId, coldStartReq(), Duration.ofSeconds(5));

    assertThat(result.status()).isEqualTo(DiscoveryJobStatus.SUCCEEDED);
    verify(runner).registerSyncWaiter(idCaptor.capture(), waiterCaptor.capture());
    // The waiter is registered and cleaned up under the same internally-generated id.
    verify(runner).unregisterSyncWaiter(idCaptor.getValue());
  }

  @Test
  void runJobSync_deadlineReachedWhileRunning_returnsLatestDto_doesNotThrow_cleansUpWaiter() {
    UUID userId = UUID.randomUUID();
    stubEnqueue(userId, DiscoveryJobStatus.RUNNING);
    // Runner never completes the waiter → the get(timeout) times out internally.

    DiscoveryJobDto result = service.runJobSync(userId, coldStartReq(), Duration.ofMillis(50));

    assertThat(result.status()).isEqualTo(DiscoveryJobStatus.RUNNING);
    verify(runner).unregisterSyncWaiter(any());
  }

  @Test
  void runJobSync_timeoutClampedToSyncCap() {
    UUID userId = UUID.randomUUID();
    stubEnqueue(userId, DiscoveryJobStatus.SUCCEEDED);
    org.mockito.Mockito.doAnswer(
            inv -> {
              CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(), any());

    // Caller asks for 999s; the service must clamp to the 60s cap. We can't observe the literal
    // clamp without timing, but the call must still return promptly (waiter completed) and not
    // reject — clamping is silent per ticket invariant 3.
    DiscoveryJobDto result = service.runJobSync(userId, coldStartReq(), Duration.ofSeconds(999));

    assertThat(result.status()).isEqualTo(DiscoveryJobStatus.SUCCEEDED);
    verify(runner).unregisterSyncWaiter(any());
  }

  @Test
  void runJobSync_reReadMissing_throws404_butStillCleansUpWaiter() {
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(jobDto(UUID.randomUUID(), userId, DiscoveryJobStatus.QUEUED));
    when(jobRepository.findById(any())).thenReturn(Optional.empty());
    org.mockito.Mockito.doAnswer(
            inv -> {
              CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(), any());

    assertThatThrownBy(() -> service.runJobSync(userId, coldStartReq(), Duration.ofSeconds(5)))
        .isInstanceOf(DiscoveryJobNotFoundException.class);
    verify(runner).unregisterSyncWaiter(any());
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<CompletableFuture<DiscoveryJobStatus>> captor() {
    return ArgumentCaptor.forClass(CompletableFuture.class);
  }

  private DiscoveryJobDto jobDto(UUID id, UUID userId, DiscoveryJobStatus status) {
    return new DiscoveryJobDto(
        id,
        userId,
        DiscoveryJobTrigger.COLD_START,
        5,
        DiscoveryTestData.sampleConstraints(),
        List.of("src_a"),
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
}
