package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit coverage of {@link DiscoveryJobStarter} and proof that {@link DiscoveryServiceImpl} routes
 * its synchronous start path through the starter bean — so {@code @Transactional} (and therefore
 * AFTER_COMMIT event publication) applies by construction, not by lazy self-injection.
 *
 * <p>Previously the persist + publish step lived on {@code DiscoveryServiceImpl.startJobWithId} and
 * was reached via an {@code @Lazy DiscoveryService self} field — a Spring self-invocation
 * workaround. Extracting the step onto a separate bean removes the workaround entirely.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryJobStarterTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private DiscoveryScrapeLogRepository scrapeLogRepository;
  @Mock private DiscoveryJobMapper jobMapper;
  @Mock private DiscoverySourceMapper sourceMapper;
  @Mock private DiscoveryScrapeLogMapper scrapeLogMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DiscoveryJobRunner runner;

  @Mock
  private com.example.mealprep.preference.domain.service.HardConstraintFilterService
      hardConstraintFilter;

  private DiscoveryJobStarter starter;

  @BeforeEach
  void setUp() {
    // No hard constraints by default — the server snapshot union is a pass-through. Lenient: the
    // guard-clause tests throw before the enqueue path reaches the assembler.
    org.mockito.Mockito.lenient()
        .when(hardConstraintFilter.exclusionKeySnapshot(any(), any()))
        .thenReturn(java.util.Set.of());
    starter =
        new DiscoveryJobStarter(
            jobRepository,
            sourceRepository,
            jobMapper,
            eventPublisher,
            new ObjectMapper(),
            new HardConstraintSnapshotAssembler(hardConstraintFilter));
  }

  // ---------- DiscoveryJobStarter direct coverage ----------

  @Test
  void startJobWithId_persistsAndPublishesEvent_returnsMappedDto() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    DiscoveryJobDto result = starter.startJobWithId(userId, req, jobId);

    assertThat(result).isNotNull();
    ArgumentCaptor<DiscoveryJob> savedJob = ArgumentCaptor.forClass(DiscoveryJob.class);
    verify(jobRepository).saveAndFlush(savedJob.capture());
    assertThat(savedJob.getValue().getId()).isEqualTo(jobId);
    assertThat(savedJob.getValue().getStatus()).isEqualTo(DiscoveryJobStatus.QUEUED);

    ArgumentCaptor<DiscoveryJobStartedEvent> evt =
        ArgumentCaptor.forClass(DiscoveryJobStartedEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().jobId()).isEqualTo(jobId);
  }

  @Test
  void startJobWithId_zeroEnabledSources_throws_doesNotPersistOrPublish() {
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    assertThatThrownBy(() -> starter.startJobWithId(UUID.randomUUID(), req, UUID.randomUUID()))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("zero enabled sources");
    verify(jobRepository, never()).saveAndFlush(any(DiscoveryJob.class));
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void startJobWithId_unknownSourceKey_throws() {
    when(sourceRepository.findBySourceKeyIn(anyList())).thenReturn(List.of());
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_unknown"),
            null);

    assertThatThrownBy(() -> starter.startJobWithId(UUID.randomUUID(), req, UUID.randomUUID()))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("unknown or disabled source keys");
  }

  @Test
  void startJobWithId_userDisabledSourceKey_throws_distinctMessage() {
    // Naming a user-disabled source → 422 like an admin-disabled one, with the distinct
    // "disabled by you" vocabulary (ticket discovery-user-source-disable).
    DiscoverySource userDisabled = DiscoveryTestData.sampleSource("src_ud");
    userDisabled.setUserDisabled(true);
    when(sourceRepository.findBySourceKeyIn(anyList())).thenReturn(List.of(userDisabled));
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_ud"),
            null);

    assertThatThrownBy(() -> starter.startJobWithId(UUID.randomUUID(), req, UUID.randomUUID()))
        .isInstanceOf(DiscoveryConstraintInvalidException.class)
        .hasMessageContaining("disabled by you")
        .hasMessageContaining("src_ud");
  }

  @Test
  void startJobWithId_defaultSourceSet_excludesUserDisabledRows() {
    // Default "all enabled sources" resolution applies enabled && !userDisabled: the
    // user-disabled row must not land in the persisted sourcesRequested.
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    DiscoverySource hidden = DiscoveryTestData.sampleSource("src_hidden");
    hidden.setUserDisabled(true);
    when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(a, hidden));
    when(jobRepository.saveAndFlush(any(DiscoveryJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubDto(userId, DiscoveryJobStatus.QUEUED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    starter.startJobWithId(userId, req, jobId);

    ArgumentCaptor<DiscoveryJob> savedJob = ArgumentCaptor.forClass(DiscoveryJob.class);
    verify(jobRepository).saveAndFlush(savedJob.capture());
    assertThat(savedJob.getValue().getSourcesRequested()).containsExactly("src_a");
  }

  // ---------- @Transactional contract on the new bean ----------

  @Test
  void startJobWithId_isAnnotatedTransactional_soProxyAdviceFires() throws NoSuchMethodException {
    // The cross-cutting concern that was previously routed via @Lazy self is just @Transactional
    // (and the AFTER_COMMIT event semantics that depend on a tx being active). Verifying the
    // annotation pins the contract so future edits don't silently strip it.
    Method m =
        DiscoveryJobStarter.class.getMethod(
            "startJobWithId", UUID.class, StartDiscoveryJobRequest.class, UUID.class);
    assertThat(m.getAnnotation(Transactional.class))
        .as("startJobWithId must be @Transactional so AFTER_COMMIT event publication works")
        .isNotNull();
  }

  // ---------- proxy-by-construction: DiscoveryServiceImpl.runJobSync delegates to the bean
  // ----------

  @Test
  void runJobSync_callsStarter_throughInjectedBean_notSelfReference(
      @Mock DiscoveryJobStarter mockStarter) {
    // The whole point of extracting the starter onto its own bean: when DiscoveryServiceImpl calls
    // jobStarter.startJobWithId(...), it goes through Spring's proxy of THAT bean. A
    // Mockito mock proves the call crosses the bean boundary — a previous this.startJobWithId()
    // self-invocation would never invoke the mock.
    DiscoveryServiceImpl service =
        new DiscoveryServiceImpl(
            jobRepository,
            sourceRepository,
            scrapeLogRepository,
            jobMapper,
            sourceMapper,
            scrapeLogMapper,
            runner,
            new DiscoveryProperties(
                Duration.ofMinutes(10),
                30,
                Duration.ofSeconds(60),
                Duration.ofHours(1),
                Duration.ofHours(6),
                null,
                false,
                null),
            mockStarter);

    UUID userId = UUID.randomUUID();
    // Complete the sync waiter immediately so runJobSync returns without timing out.
    org.mockito.Mockito.doAnswer(
            inv -> {
              CompletableFuture<DiscoveryJobStatus> w = inv.getArgument(1);
              w.complete(DiscoveryJobStatus.SUCCEEDED);
              return null;
            })
        .when(runner)
        .registerSyncWaiter(any(UUID.class), any());
    // Re-read returns SUCCEEDED.
    when(jobRepository.findById(any(UUID.class)))
        .thenAnswer(
            inv -> {
              DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
              job.setId(inv.getArgument(0));
              job.setStatus(DiscoveryJobStatus.SUCCEEDED);
              return Optional.of(job);
            });
    when(jobMapper.toDto(any(DiscoveryJob.class)))
        .thenReturn(stubDto(userId, DiscoveryJobStatus.SUCCEEDED));

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    DiscoveryJobDto result = service.runJobSync(userId, req, Duration.ofSeconds(5));

    assertThat(result.status()).isEqualTo(DiscoveryJobStatus.SUCCEEDED);
    // The bean boundary is what makes @Transactional on startJobWithId actually apply.
    verify(mockStarter, times(1)).startJobWithId(eq(userId), eq(req), any(UUID.class));
  }

  // ---------- helpers ----------

  private DiscoveryJobDto stubDto(UUID userId, DiscoveryJobStatus status) {
    return new DiscoveryJobDto(
        UUID.randomUUID(),
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
