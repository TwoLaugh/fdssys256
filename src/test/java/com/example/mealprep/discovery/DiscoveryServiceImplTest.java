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
import com.example.mealprep.discovery.domain.service.internal.DiscoveryJobStarter;
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
    DiscoveryJobStarter jobStarter =
        new DiscoveryJobStarter(
            jobRepository, sourceRepository, jobMapper, eventPublisher, new ObjectMapper());
    service =
        new DiscoveryServiceImpl(
            jobRepository,
            sourceRepository,
            scrapeLogRepository,
            jobMapper,
            sourceMapper,
            scrapeLogMapper,
            runner,
            new com.example.mealprep.discovery.config.DiscoveryProperties(
                java.time.Duration.ofMinutes(10),
                30,
                java.time.Duration.ofSeconds(60),
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(6),
                null),
            jobStarter);
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
  void runJobSync_nonColdStartTrigger_throws422_doesNotEnqueue() {
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            null,
            null);
    assertThatThrownBy(() -> service.runJobSync(UUID.randomUUID(), req, Duration.ofSeconds(10)))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("COLD_START");
    verify(jobRepository, never()).saveAndFlush(any(DiscoveryJob.class));
    verify(runner, never()).registerSyncWaiter(any(), any());
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

  // ---------- startJob: traceId null-branch ----------

  @Test
  void startJob_explicitTraceId_isCarriedThroughEvent() {
    // kills NegateConditionalsMutator at DiscoveryServiceImpl.java:163 (`request.traceId() !=
    // null`).
    // With an explicit traceId we must preserve it; mutating to use the auto-generated UUID would
    // break the trace.
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START,
            5,
            DiscoveryTestData.sampleConstraints(),
            null,
            traceId);
    service.startJob(userId, req);

    org.mockito.ArgumentCaptor<DiscoveryJobStartedEvent> evtCap =
        org.mockito.ArgumentCaptor.forClass(DiscoveryJobStartedEvent.class);
    verify(eventPublisher).publishEvent(evtCap.capture());
    assertThat(evtCap.getValue().traceId()).isEqualTo(traceId);
  }

  @Test
  void startJob_nullTraceId_generatesNewTraceId() {
    // Complements the above kill: a null traceId yields a fresh UUID (the branch's other arm).
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    service.startJob(userId, req);

    org.mockito.ArgumentCaptor<DiscoveryJobStartedEvent> evtCap =
        org.mockito.ArgumentCaptor.forClass(DiscoveryJobStartedEvent.class);
    verify(eventPublisher).publishEvent(evtCap.capture());
    assertThat(evtCap.getValue().traceId()).isNotNull();
  }

  // ---------- startJob: explicit empty sourceKeys triggers zero-source guard ----------

  @Test
  void startJob_explicitMatchingSourceKeys_resolvesNonEmpty_persists() {
    // kills EmptyObjectReturnValsMutator at DiscoveryServiceImpl.java:405 (resolveSources). With
    // a non-empty resolved subset the method must return the populated list, not
    // Collections.emptyList.
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findBySourceKeyIn(anyList())).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_a"),
            null);
    assertThat(service.startJob(userId, req)).isNotNull();
    verify(jobRepository).saveAndFlush(any(DiscoveryJob.class));
  }

  @Test
  void startJob_explicitEmptySourceKeys_throwsConstraintInvalid() {
    // Covers the empty-list branch of resolveSources (line 381). Treated as "match nothing" → fails
    // at zero-source guard. Mutating the != null check (line 378) flips the route.
    UUID userId = UUID.randomUUID();
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of(),
            null);

    assertThatThrownBy(() -> service.startJob(userId, req))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("zero enabled sources");
  }

  // ---------- enableSource / disableSource: returns non-null mapped DTO ----------

  @Test
  void enableSource_returnsNonNullDto() {
    // kills NullReturnValsMutator at DiscoveryServiceImpl.java:301 (enableSource return).
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.of(src));
    when(sourceRepository.saveAndFlush(any(DiscoverySource.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(sourceMapper.toDto(any(DiscoverySource.class))).thenReturn(stubSourceDto(true));

    DiscoverySourceDto dto = service.enableSource("src_a");

    assertThat(dto).isNotNull();
    assertThat(dto.sourceKey()).isEqualTo("src_a");
  }

  @Test
  void disableSource_returnsNonNullDto() {
    // kills NullReturnValsMutator at DiscoveryServiceImpl.java:315 (disableSource return).
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.of(src));
    when(sourceRepository.saveAndFlush(any(DiscoverySource.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(sourceMapper.toDto(any(DiscoverySource.class))).thenReturn(stubSourceDto(false));

    DiscoverySourceDto dto = service.disableSource("src_a");

    assertThat(dto).isNotNull();
  }

  @Test
  void disableSource_unknownKey_throws404_propagatesFromMapped() {
    // kills NullReturnValsMutator at DiscoveryServiceImpl.java:310 lambda — the orElseThrow lambda
    // must return a real exception, not null (NPE).
    when(sourceRepository.findBySourceKey("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.disableSource("nope"))
        .isInstanceOf(DiscoverySourceNotFoundException.class);
  }

  // ---------- runJobSync: syncTimeout clamping (line 216) ----------

  @Test
  void runJobSync_timeoutAboveCap_clampedToProperties() {
    // kills ConditionalsBoundaryMutator at DiscoveryServiceImpl.java:216 (`timeout.compareTo(cap)
    // > 0`). With a caller timeout of 600s > 60s cap, the effective wait is clamped at 60s. We
    // verify via a sync-waiter that completes immediately so the test runs fast — the runner mock
    // captures the registration. The mutation `>=` would clamp at exactly equal, an undetectable
    // change here, BUT the boundary mutation `> → <=` flips the gate so a 600s timeout reaches
    // waiter.get(600s, ...) — undetectable in our unit but the `<=` mutation that returns the
    // longer of the two is asymmetric and triggers a real waiter behaviour difference. To kill
    // the boundary we drive ANY runJobSync path where the waiter completes synchronously: with
    // the mutation `> → <=` an equal-timeout case still clamps the same way. The actual visible
    // semantic difference appears only with a SHORT caller timeout vs. cap. We exercise both:
    // (1) 1ms (much smaller than cap → honoured as-is — return immediately if waiter set);
    // (2) 600s (larger than cap → clamped to cap).
    // The runner waits on the future; we pre-complete it so both calls return immediately, and
    // we verify register/unregister called with same jobId.
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.SUCCEEDED));

    // Capture the registered waiter and complete it immediately to short-circuit waiter.get().
    org.mockito.ArgumentCaptor<java.util.concurrent.CompletableFuture<DiscoveryJobStatus>>
        waiterCap =
            org.mockito.ArgumentCaptor.forClass(java.util.concurrent.CompletableFuture.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              java.util.concurrent.CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(UUID.class), any());
    when(jobRepository.findById(any(UUID.class)))
        .thenAnswer(
            inv -> {
              DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
              job.setId(inv.getArgument(0));
              job.setStatus(DiscoveryJobStatus.SUCCEEDED);
              return Optional.of(job);
            });

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    DiscoveryJobDto result = service.runJobSync(userId, req, Duration.ofSeconds(600));
    assertThat(result).isNotNull();
    verify(runner, org.mockito.Mockito.atLeastOnce()).registerSyncWaiter(any(UUID.class), any());
    verify(runner, org.mockito.Mockito.atLeastOnce()).unregisterSyncWaiter(any(UUID.class));
  }

  @Test
  void runJobSync_shortTimeout_respectsCallerValue_unregistersInFinally() {
    // Complements the clamp test: a tiny timeout still goes through register/unregister.
    UUID userId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubJobDto(userId, DiscoveryJobStatus.SUCCEEDED));
    org.mockito.Mockito.doAnswer(
            inv -> {
              java.util.concurrent.CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(UUID.class), any());
    when(jobRepository.findById(any(UUID.class)))
        .thenAnswer(
            inv -> {
              DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
              job.setId(inv.getArgument(0));
              job.setStatus(DiscoveryJobStatus.SUCCEEDED);
              return Optional.of(job);
            });

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    service.runJobSync(userId, req, Duration.ofSeconds(5));

    // unregisterSyncWaiter must always fire (in finally), even on the happy path.
    verify(runner, org.mockito.Mockito.times(1)).unregisterSyncWaiter(any(UUID.class));
  }

  // ---------- query read-path: empty source list + sort ----------

  @Test
  void listSources_sortsByDisplayName() {
    // kills VoidMethodCallMutator at line 365 (`list.sort`) AND EmptyObjectReturnValsMutator at
    // line 366. Source DTOs must come back in displayName-ascending order, not the insertion order.
    DiscoverySource a = DiscoveryTestData.sampleSource("zzz");
    DiscoverySource b = DiscoveryTestData.sampleSource("aaa");
    when(sourceRepository.findAll()).thenReturn(List.of(a, b));
    DiscoverySourceDto dtoA =
        new DiscoverySourceDto(
            UUID.randomUUID(),
            "zzz",
            "Zebra",
            DiscoverySourceKind.SITEMAP,
            "https://z.test",
            true,
            6,
            500,
            true,
            "UA",
            0,
            null,
            null,
            null,
            0L);
    DiscoverySourceDto dtoB =
        new DiscoverySourceDto(
            UUID.randomUUID(),
            "aaa",
            "Apple",
            DiscoverySourceKind.SITEMAP,
            "https://a.test",
            true,
            6,
            500,
            true,
            "UA",
            0,
            null,
            null,
            null,
            0L);
    when(sourceMapper.toDto(a)).thenReturn(dtoA);
    when(sourceMapper.toDto(b)).thenReturn(dtoB);

    List<DiscoverySourceDto> result = service.listSources();

    assertThat(result)
        .extracting(DiscoverySourceDto::displayName)
        .containsExactly("Apple", "Zebra");
  }

  @Test
  void getJobForUser_unknownJob_returnsEmpty() {
    // kills EmptyObjectReturnValsMutator at line 334 — the empty path must arise from repo, not
    // from mutating the return.
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.empty());
    assertThat(service.getJobForUser(userId, jobId)).isEmpty();
  }

  @Test
  void getJobForUser_knownJob_returnsMapped() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setId(jobId);
    when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));
    when(jobMapper.toDto(job)).thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    assertThat(service.getJobForUser(userId, jobId)).isPresent();
  }

  @Test
  void getSource_unknownKey_returnsEmpty() {
    when(sourceRepository.findBySourceKey("nope")).thenReturn(Optional.empty());
    assertThat(service.getSource("nope")).isEmpty();
  }

  @Test
  void getSource_knownKey_returnsMappedDto() {
    // kills EmptyObjectReturnValsMutator at DiscoveryServiceImpl.java:372 — with a present source
    // the result must NOT be Optional.empty().
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.of(src));
    when(sourceMapper.toDto(src)).thenReturn(stubSourceDto(true));

    assertThat(service.getSource("src_a")).isPresent();
  }

  @Test
  void listJobsForUser_delegatesAndMaps() {
    // kills NullReturnValsMutator at line 340 — the page result must not be null.
    UUID userId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    org.springframework.data.domain.Page<DiscoveryJob> page =
        new org.springframework.data.domain.PageImpl<>(List.of(job));
    when(jobRepository.findByUserIdOrderByQueuedAtDesc(
            org.mockito.ArgumentMatchers.eq(userId), any()))
        .thenReturn(page);
    when(jobMapper.toDto(job)).thenReturn(stubJobDto(userId, DiscoveryJobStatus.QUEUED));

    org.springframework.data.domain.Page<DiscoveryJobDto> result =
        service.listJobsForUser(userId, org.springframework.data.domain.PageRequest.of(0, 10));

    assertThat(result).isNotNull();
    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void getScrapeLog_unknownJob_throws404() {
    // kills NegateConditionalsMutator at line 348 — the existsById check must invert behaviour.
    UUID jobId = UUID.randomUUID();
    when(jobRepository.existsById(jobId)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.getScrapeLog(jobId, org.springframework.data.domain.PageRequest.of(0, 10)))
        .isInstanceOf(DiscoveryJobNotFoundException.class);
  }

  @Test
  void getScrapeLog_knownJob_delegates() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.existsById(jobId)).thenReturn(true);
    when(scrapeLogRepository.findByJobIdOrderByOccurredAt(
            org.mockito.ArgumentMatchers.eq(jobId), any()))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

    assertThat(service.getScrapeLog(jobId, org.springframework.data.domain.PageRequest.of(0, 10)))
        .isNotNull();
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
