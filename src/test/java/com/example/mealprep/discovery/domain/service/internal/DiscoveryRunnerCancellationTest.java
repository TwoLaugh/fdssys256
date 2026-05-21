package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.domain.service.RobotsTxtGate;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit coverage of the in-memory cancellation flag — including the map cleanup behaviour in the
 * runner's {@code finally} block. The IT counterpart (POST /cancel against a live RUNNING row) is
 * deferred to CI since the runner thread + servlet thread interleaving is brittle on local
 * Testcontainers in a parallel-worktree environment per the gotcha doc.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryRunnerCancellationTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private SourceRegistry sourceRegistry;
  @Mock private RobotsTxtGate robotsTxtGate;
  @Mock private SourceRateLimiterRegistry rateLimiterRegistry;
  @Mock private CandidateAiFilter candidateAiFilter;
  @Mock private HardConstraintFilterService hardConstraintFilter;
  @Mock private DiscoveryJobTransitions transitions;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private com.example.mealprep.recipe.spi.RecipeWriteApi recipeWriteApi;

  private DiscoveryJobRunner runner;

  @BeforeEach
  void setUp() {
    DiscoveryProperties properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10),
            30,
            Duration.ofSeconds(60),
            Duration.ofHours(1),
            Duration.ofHours(6),
            null);
    runner =
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
            recipeWriteApi);
  }

  @Test
  void requestCancellation_addsEntryToMap() throws Exception {
    UUID jobId = UUID.randomUUID();
    runner.requestCancellation(jobId);
    assertThat(cancellationMap().get(jobId).get()).isTrue();
  }

  @Test
  void runFinally_clearsCancellationMap_evenWhenClaimReturnsEmpty() throws Exception {
    UUID jobId = UUID.randomUUID();
    runner.requestCancellation(jobId);
    when(transitions.claim(jobId)).thenReturn(Optional.empty());

    runner.run(startedEvent(jobId));

    assertThat(cancellationMap()).doesNotContainKey(jobId);
  }

  @Test
  void runFinally_clearsCancellationMap_afterFinalise() throws Exception {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = org.mockito.Mockito.mock(DiscoverySource.class);
    lenient().when(source.key()).thenReturn("src_a");
    lenient().when(source.robotsTxtUri()).thenReturn(Optional.empty());

    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate cand =
        new DiscoveryCandidate("src_a", "https://example.test/r/1", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(cand));
    when(candidateAiFilter.filter(anyList(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
    // The cancellation flag is checked BEFORE fetchRecipe / hardConstraint per iteration, so these
    // stubs are unused but declared lenient to document the otherwise-happy-path shape.
    lenient()
        .when(source.fetchRecipe(cand))
        .thenReturn(
            new ParsedRecipe(
                "https://example.test/r/1",
                "Recipe",
                "desc",
                List.of(
                    new ParsedRecipe.ParsedIngredient(
                        "Salt", "salt", BigDecimal.ONE, "tsp", null, false)),
                List.of(new ParsedRecipe.ParsedMethodStep(1, "Mix.", null)),
                new ParsedRecipe.ParsedRecipeMetadata(
                    2, 5, 10, 15, List.of(), "Asian", List.of("dinner")),
                "jsonld",
                new BigDecimal("0.9")));
    lenient()
        .when(hardConstraintFilter.check(any(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient().when(transitions.scrapeLogExistsSince(anyString(), any())).thenReturn(false);
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.requestCancellation(jobId);
    runner.run(startedEvent(jobId));

    // finally runs after run() completes
    assertThat(cancellationMap()).doesNotContainKey(jobId);
    // Cancel flag interruption should produce a "cancelled by user" finalise.
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            eq("cancelled by user"),
            anyList(),
            anyList());
  }

  @Test
  void runFinally_clearsMap_evenWhenRunnerThrows() throws Exception {
    UUID jobId = UUID.randomUUID();
    runner.requestCancellation(jobId);
    when(transitions.claim(jobId)).thenThrow(new RuntimeException("boom"));
    // jobRepository.findById is invoked by the catch-block recovery path.
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    runner.run(startedEvent(jobId));

    assertThat(cancellationMap()).doesNotContainKey(jobId);
    verify(transitions, never()).finaliseTo(any(), any(), any(), any(), any());
  }

  @SuppressWarnings("unchecked")
  private ConcurrentHashMap<UUID, AtomicBoolean> cancellationMap() throws Exception {
    Field f = DiscoveryJobRunner.class.getDeclaredField("cancellationRequests");
    f.setAccessible(true);
    return (ConcurrentHashMap<UUID, AtomicBoolean>) f.get(runner);
  }

  private DiscoveryJobStartedEvent startedEvent(UUID jobId) {
    return new DiscoveryJobStartedEvent(
        jobId,
        UUID.randomUUID(),
        DiscoveryJobTrigger.COLD_START,
        1,
        List.of("src_a"),
        UUID.randomUUID(),
        Instant.now());
  }
}
