package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.domain.service.RobotsTxtGate;
import com.example.mealprep.discovery.event.DiscoveryJobCompletedEvent;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.ImportedRecipeResult;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Concurrency coverage for the opt-in cross-source search fan-out (feature flag {@code
 * mealprep.discovery.parallel-sources=true}). Proves, with the flag ON and a REAL bounded executor:
 *
 * <ul>
 *   <li>all active sources contribute to the merged candidate list (both sources fetched);
 *   <li>one source throwing is recorded as failed while the others still succeed (PARTIAL);
 *   <li>the requested-count quota is respected (extra candidates → {@code JOB_QUOTA_REACHED});
 *   <li>a cancellation request finalises the job exactly once (no double-finalise /
 *       double-publish);
 *   <li>a barrier-synchronised stress run with several sources shakes out lost-update / CME races
 *       in the shared {@code merged} / {@code sourcesFailed} accumulators — deterministic, no
 *       {@code Thread.sleep}.
 * </ul>
 *
 * <p>A final test pins the flag-OFF (sequential) path: with a real pool wired but the flag false,
 * the fan-out executor is never used and the sources run inline in request order.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryRunnerParallelSourcesTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private SourceRegistry sourceRegistry;
  @Mock private RobotsTxtGate robotsTxtGate;
  @Mock private SourceRateLimiterRegistry rateLimiterRegistry;
  @Mock private CandidateAiFilter candidateAiFilter;
  @Mock private HardConstraintFilterService hardConstraintFilter;
  @Mock private DiscoveryJobTransitions transitions;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private RecipeWriteApi recipeWriteApi;

  private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private ExecutorService fanoutExecutor;

  @AfterEach
  void tearDown() {
    if (fanoutExecutor != null) {
      fanoutExecutor.shutdownNow();
    }
  }

  private DiscoveryJobRunner runnerWith(boolean parallel, ExecutorService executor) {
    DiscoveryProperties properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10),
            30,
            Duration.ofSeconds(60),
            Duration.ofHours(1),
            Duration.ofHours(6),
            null,
            parallel,
            Duration.ofSeconds(5));
    DiscoveryJobRunner runner =
        new DiscoveryJobRunner(
            jobRepository,
            sourceRepository,
            sourceRegistry,
            robotsTxtGate,
            rateLimiterRegistry,
            new ContentFingerprintHasher(),
            candidateAiFilter,
            hardConstraintFilter,
            transitions,
            eventPublisher,
            properties,
            new ObjectMapper(),
            recipeWriteApi,
            executor);
    lenient()
        .when(recipeWriteApi.saveImportedRecipe(any(ImportedRecipeData.class)))
        .thenAnswer(
            inv -> new ImportedRecipeResult(UUID.randomUUID(), UUID.randomUUID(), true, null));
    return runner;
  }

  // -------- flag ON: every active source contributes to merged results --------

  @Test
  void parallel_allSourcesContributeToMerged_bothFetchedAndIngested() {
    fanoutExecutor = Executors.newFixedThreadPool(4);
    DiscoveryJobRunner runner = runnerWith(true, fanoutExecutor);

    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = job(jobId, 2, List.of("src_a", "src_b"));

    DiscoverySource a = stubSource("src_a");
    DiscoverySource b = stubSource("src_b");
    DiscoveryCandidate ca =
        new DiscoveryCandidate("src_a", "https://a.test/r/1", "A", "D", Map.of());
    DiscoveryCandidate cb =
        new DiscoveryCandidate("src_b", "https://b.test/r/1", "B", "D", Map.of());
    when(a.search(any(DiscoveryQuery.class))).thenReturn(List.of(ca));
    when(b.search(any(DiscoveryQuery.class))).thenReturn(List.of(cb));
    when(a.fetchRecipe(ca)).thenReturn(parsed("https://a.test/r/1"));
    when(b.fetchRecipe(cb)).thenReturn(parsed("https://b.test/r/1"));

    wireHappyPath(jobId, job, List.of(a, b));

    runner.run(startedEvent(jobId, List.of("src_a", "src_b")));

    // Both sources were searched AND both candidates fetched → both contributed to merged.
    verify(a).search(any(DiscoveryQuery.class));
    verify(b).search(any(DiscoveryQuery.class));
    verify(a).fetchRecipe(ca);
    verify(b).fetchRecipe(cb);
    // candidatesSeen == 2 → the merge collected from both sources with no lost update.
    verify(transitions).recordCandidatesSeen(jobId, 2);
  }

  // -------- flag ON: one source throws → recorded failed, the other still succeeds --------

  @Test
  void parallel_oneSourceThrows_recordedFailed_otherSucceeds_partialOutcome() {
    fanoutExecutor = Executors.newFixedThreadPool(4);
    DiscoveryJobRunner runner = runnerWith(true, fanoutExecutor);

    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = job(jobId, 5, List.of("src_a", "src_b"));

    DiscoverySource a = stubSource("src_a");
    DiscoverySource b = stubSource("src_b");
    DiscoveryCandidate ca =
        new DiscoveryCandidate("src_a", "https://a.test/r/1", "A", "D", Map.of());
    when(a.search(any(DiscoveryQuery.class))).thenReturn(List.of(ca));
    when(b.search(any(DiscoveryQuery.class)))
        .thenThrow(new DiscoverySourceUnavailableException("src_b", "5xx storm", null));
    when(a.fetchRecipe(ca)).thenReturn(parsed("https://a.test/r/1"));

    wireHappyPath(jobId, job, List.of(a, b));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId, List.of("src_a", "src_b")));

    // The throwing source is recorded as failed (circuit-breaker bookkeeping), the other ingests.
    verify(sourceRegistry).recordFailure("src_b");
    verify(a).fetchRecipe(ca);
    // Terminal finalise carries src_b in the failed list and src_a in the succeeded list.
    ArgumentCaptor<List<String>> succeeded = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> failed = ArgumentCaptor.forClass(List.class);
    verify(transitions)
        .finaliseTo(eq(jobId), any(), anyString(), succeeded.capture(), failed.capture());
    assertThat(failed.getValue()).contains("src_b");
    assertThat(succeeded.getValue()).contains("src_a");
  }

  // -------- flag ON: requested-count quota respected after parallel merge --------

  @Test
  void parallel_quotaRespected_extraCandidatesSkippedQuotaReached() {
    fanoutExecutor = Executors.newFixedThreadPool(4);
    DiscoveryJobRunner runner = runnerWith(true, fanoutExecutor);

    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = job(jobId, 1, List.of("src_a", "src_b")); // quota == 1

    DiscoverySource a = stubSource("src_a");
    DiscoverySource b = stubSource("src_b");
    DiscoveryCandidate ca =
        new DiscoveryCandidate("src_a", "https://a.test/r/1", "A", "D", Map.of());
    DiscoveryCandidate cb =
        new DiscoveryCandidate("src_b", "https://b.test/r/1", "B", "D", Map.of());
    when(a.search(any(DiscoveryQuery.class))).thenReturn(List.of(ca));
    when(b.search(any(DiscoveryQuery.class))).thenReturn(List.of(cb));
    // Only the first candidate (deterministic active-order: src_a) is fetched; the second hits the
    // quota gate.
    lenient().when(a.fetchRecipe(ca)).thenReturn(parsed("https://a.test/r/1"));
    lenient().when(b.fetchRecipe(cb)).thenReturn(parsed("https://b.test/r/1"));

    wireHappyPath(jobId, job, List.of(a, b));

    runner.run(startedEvent(jobId, List.of("src_a", "src_b")));

    // Exactly one ingest happened (quota == 1).
    verify(transitions, times(1)).incrementIngested(jobId);
    // The over-quota candidate produced a JOB_QUOTA_REACHED scrape row.
    ArgumentCaptor<DiscoveryScrapeLog> rows = ArgumentCaptor.forClass(DiscoveryScrapeLog.class);
    verify(transitions, atLeastOnce()).writeScrapeRow(rows.capture());
    boolean sawQuotaRow =
        rows.getAllValues().stream()
            .anyMatch(
                r ->
                    r.getSkipReason()
                        == com.example.mealprep.discovery.domain.entity.ScrapeSkipReason
                            .JOB_QUOTA_REACHED);
    assertThat(sawQuotaRow).as("over-quota candidate skipped as JOB_QUOTA_REACHED").isTrue();
  }

  // -------- flag ON: cancellation finalises exactly once --------

  @Test
  void parallel_cancellationMidRun_finalisesOnce_noDoublePublish() {
    fanoutExecutor = Executors.newFixedThreadPool(4);
    DiscoveryJobRunner runner = runnerWith(true, fanoutExecutor);

    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = job(jobId, 2, List.of("src_a", "src_b"));

    DiscoverySource a = stubSource("src_a");
    DiscoverySource b = stubSource("src_b");
    DiscoveryCandidate ca =
        new DiscoveryCandidate("src_a", "https://a.test/r/1", "A", "D", Map.of());
    DiscoveryCandidate cb =
        new DiscoveryCandidate("src_b", "https://b.test/r/1", "B", "D", Map.of());
    when(a.search(any(DiscoveryQuery.class))).thenReturn(List.of(ca));
    when(b.search(any(DiscoveryQuery.class))).thenReturn(List.of(cb));
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(a, b));
    when(sourceRegistry.isCircuitOpen(any(), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire(anyString())).thenReturn(true);
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> CandidateFilterOutcome.keepAll(inv.getArgument(0)));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
    // Mirror the real terminal-state guard: first finalise transitions; any later one is a no-op.
    when(transitions.finaliseTo(eq(jobId), any(), any(), anyList(), anyList()))
        .thenReturn(Optional.of(job))
        .thenReturn(Optional.empty());

    runner.requestCancellation(jobId);
    runner.run(startedEvent(jobId, List.of("src_a", "src_b")));

    // The search phase still ran (sources searched), but the per-candidate fetch loop saw the
    // cancel
    // flag and finalised before fetching anything.
    verify(a, never()).fetchRecipe(any());
    verify(b, never()).fetchRecipe(any());
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            eq("cancelled by user"),
            anyList(),
            anyList());
    // Exactly-once completion publish — the post-fetch finaliseTerminal sees Optional.empty().
    verify(eventPublisher, times(1)).publishEvent(any(DiscoveryJobCompletedEvent.class));
  }

  // -------- flag ON: barrier-synchronised stress over several sources, no races --------

  @Test
  void parallel_stress_manySourcesBarrierSynced_allContribute_noLostUpdatesOrCme()
      throws Exception {
    int sourceCount = 6;
    fanoutExecutor = Executors.newFixedThreadPool(sourceCount);
    DiscoveryJobRunner runner = runnerWith(true, fanoutExecutor);

    UUID jobId = UUID.randomUUID();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < sourceCount; i++) {
      keys.add("src_" + i);
    }
    DiscoveryJob job = job(jobId, sourceCount, keys);

    // A barrier the width of the source count forces every search() to be in-flight simultaneously
    // before any returns — maximally exercising concurrent access to the shared accumulators. The
    // barrier is deterministic (no Thread.sleep) and times out fast if the pool can't run them all.
    CyclicBarrier barrier = new CyclicBarrier(sourceCount);
    AtomicInteger concurrentPeak = new AtomicInteger();
    AtomicInteger inFlight = new AtomicInteger();
    List<DiscoverySource> sources = new ArrayList<>();
    List<DiscoveryCandidate> expected = new CopyOnWriteArrayList<>();
    for (int i = 0; i < sourceCount; i++) {
      String key = "src_" + i;
      DiscoverySource src = stubSource(key);
      DiscoveryCandidate cand =
          new DiscoveryCandidate(key, "https://" + key + ".test/r/1", key, "D", Map.of());
      expected.add(cand);
      when(src.search(any(DiscoveryQuery.class)))
          .thenAnswer(
              inv -> {
                int now = inFlight.incrementAndGet();
                concurrentPeak.accumulateAndGet(now, Math::max);
                barrier.await(5, TimeUnit.SECONDS); // rendezvous: all sources overlap here
                inFlight.decrementAndGet();
                return List.of(cand);
              });
      lenient().when(src.fetchRecipe(cand)).thenReturn(parsed(cand.candidateUrl()));
      sources.add(src);
    }

    wireHappyPath(jobId, job, sources);

    runner.run(startedEvent(jobId, keys));

    // The barrier proves genuine overlap: every source's search() was in-flight at once.
    assertThat(concurrentPeak.get()).isEqualTo(sourceCount);
    // No lost updates: all sourceCount candidates survived the concurrent merge (candidatesSeen).
    verify(transitions).recordCandidatesSeen(jobId, sourceCount);
    // Every source contributed an ingest (no CME / dropped candidate).
    verify(transitions, times(sourceCount)).incrementIngested(jobId);
  }

  // -------- flag OFF: sequential path preserved, executor never used --------

  @Test
  void sequential_flagOff_sourcesRunInline_fanoutExecutorUntouched() {
    // A real pool is wired but the flag is OFF; the runner must never submit to it.
    ExecutorService spyPool = Executors.newSingleThreadExecutor();
    AtomicInteger submissions = new AtomicInteger();
    ExecutorService tracking =
        new java.util.concurrent.AbstractExecutorService() {
          @Override
          public void execute(Runnable command) {
            submissions.incrementAndGet();
            spyPool.execute(command);
          }

          @Override
          public void shutdown() {}

          @Override
          public List<Runnable> shutdownNow() {
            return List.of();
          }

          @Override
          public boolean isShutdown() {
            return false;
          }

          @Override
          public boolean isTerminated() {
            return false;
          }

          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
          }
        };
    try {
      DiscoveryJobRunner runner = runnerWith(false, tracking);

      UUID jobId = UUID.randomUUID();
      DiscoveryJob job = job(jobId, 2, List.of("src_a", "src_b"));

      DiscoverySource a = stubSource("src_a");
      DiscoverySource b = stubSource("src_b");
      DiscoveryCandidate ca =
          new DiscoveryCandidate("src_a", "https://a.test/r/1", "A", "D", Map.of());
      DiscoveryCandidate cb =
          new DiscoveryCandidate("src_b", "https://b.test/r/1", "B", "D", Map.of());
      when(a.search(any(DiscoveryQuery.class))).thenReturn(List.of(ca));
      when(b.search(any(DiscoveryQuery.class))).thenReturn(List.of(cb));
      when(a.fetchRecipe(ca)).thenReturn(parsed("https://a.test/r/1"));
      when(b.fetchRecipe(cb)).thenReturn(parsed("https://b.test/r/1"));

      wireHappyPath(jobId, job, List.of(a, b));

      runner.run(startedEvent(jobId, List.of("src_a", "src_b")));

      // Same observable outcome as parallel — both contributed — but ZERO executor submissions.
      verify(transitions).recordCandidatesSeen(jobId, 2);
      assertThat(submissions.get())
          .as("flag-off path must not submit to the fan-out pool")
          .isZero();
    } finally {
      spyPool.shutdownNow();
    }
  }

  // -------- helpers --------

  /**
   * Wires the collaborators shared by the happy-path tests: claim, source resolution, circuit
   * breaker, rate-limit, AI filter pass-through, hard-constraint pass, dedup miss, and the job
   * lookup used by finalise. Individual {@code search}/{@code fetchRecipe} stubs are set per test.
   */
  private void wireHappyPath(UUID jobId, DiscoveryJob job, List<DiscoverySource> sources) {
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(new ArrayList<>(sources));
    when(sourceRegistry.isCircuitOpen(any(), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire(anyString())).thenReturn(true);
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> CandidateFilterOutcome.keepAll(inv.getArgument(0)));
    lenient()
        .when(hardConstraintFilter.check(eq(USER_ID), anyList(), any()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient().when(transitions.scrapeLogExistsSince(anyString(), any())).thenReturn(false);
    lenient().when(sourceRepository.findBySourceKey(anyString())).thenReturn(Optional.empty());
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
  }

  private DiscoveryJob job(UUID jobId, int requestedCount, List<String> sources) {
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(requestedCount);
    job.setSourcesRequested(new ArrayList<>(sources));
    return job;
  }

  private DiscoverySource stubSource(String key) {
    DiscoverySource src = mock(DiscoverySource.class);
    lenient().when(src.key()).thenReturn(key);
    lenient().when(src.robotsTxtUri()).thenReturn(Optional.empty());
    return src;
  }

  private ParsedRecipe parsed(String url) {
    return new ParsedRecipe(
        url,
        "Recipe " + url,
        "desc",
        List.of(
            new ParsedRecipe.ParsedIngredient("Salt", "salt", BigDecimal.ONE, "tsp", null, false)),
        List.of(new ParsedRecipe.ParsedMethodStep(1, "Mix.", null)),
        new ParsedRecipe.ParsedRecipeMetadata(2, 5, 10, 15, List.of(), "Asian", List.of("dinner")),
        "jsonld",
        new BigDecimal("0.9"));
  }

  private DiscoveryJobStartedEvent startedEvent(UUID jobId, List<String> sources) {
    return new DiscoveryJobStartedEvent(
        jobId,
        USER_ID,
        DiscoveryJobTrigger.COLD_START,
        sources.size(),
        sources,
        UUID.randomUUID(),
        Instant.now());
  }
}
