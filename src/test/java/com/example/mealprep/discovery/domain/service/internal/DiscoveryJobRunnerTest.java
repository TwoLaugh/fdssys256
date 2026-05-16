package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.domain.service.RobotsTxtGate;
import com.example.mealprep.discovery.event.DiscoveryJobCompletedEvent;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.ViolationKind;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests over the 01d runner. The runner orchestrates many collaborators; this suite verifies
 * the branching/decisions via Mockito stubs. The Testcontainers-backed flow lives in {@code
 * DiscoveryRunnerIT}.
 *
 * <p>SKELETON-MODE NOTE: this 01d ships without the {@code RecipeWriteApi.saveImportedRecipe} SPI
 * (that lands in {@code recipe-01l}). The persist step currently writes an {@code
 * EXTRACTION_FAILED} row with {@code error_class = "saveImportedRecipeNotYetImplemented"}; tests
 * assert that shape rather than the eventual SUCCESS shape.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryJobRunnerTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private SourceRegistry sourceRegistry;
  @Mock private RobotsTxtGate robotsTxtGate;
  @Mock private SourceRateLimiterRegistry rateLimiterRegistry;
  @Mock private CandidateAiFilter candidateAiFilter;
  @Mock private HardConstraintFilterService hardConstraintFilter;
  @Mock private DiscoveryJobTransitions transitions;
  @Mock private ApplicationEventPublisher eventPublisher;

  private DiscoveryJobRunner runner;

  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @BeforeEach
  void setUp() {
    DiscoveryProperties properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10), 30, Duration.ofSeconds(60), Duration.ofHours(1));
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
            new ObjectMapper());
  }

  // -------- claim --------

  @Test
  void run_claimReturnsEmpty_runnerExitsCleanly() {
    UUID jobId = UUID.randomUUID();
    when(transitions.claim(jobId)).thenReturn(Optional.empty());

    runner.run(startedEvent(jobId));

    verify(transitions).claim(jobId);
    verify(transitions, never()).finaliseTo(any(), any(), any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any(DiscoveryJobCompletedEvent.class));
  }

  // -------- happy-ish path: skeleton mode persist writes EXTRACTION_FAILED --------

  @Test
  void run_oneSourceOneCandidate_skeletonModeWritesExtractionFailedRow_jobFailed() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate candidate =
        new DiscoveryCandidate("src_a", "https://example.test/r/1", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(candidate));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(source.fetchRecipe(candidate)).thenReturn(sampleParsedRecipe(new BigDecimal("0.9")));
    when(hardConstraintFilter.check(eq(USER_ID), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    when(transitions.scrapeLogExistsSince(anyString(), any())).thenReturn(false);
    lenient().when(sourceRepository.findBySourceKey("src_a")).thenReturn(Optional.empty());
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    ArgumentCaptor<DiscoveryScrapeLog> rows = ArgumentCaptor.forClass(DiscoveryScrapeLog.class);
    verify(transitions, atLeastOnce()).writeScrapeRow(rows.capture());
    boolean sawSkeletonRow =
        rows.getAllValues().stream()
            .anyMatch(
                r ->
                    r.getStatus() == ScrapeOutcome.EXTRACTION_FAILED
                        && "saveImportedRecipeNotYetImplemented".equals(r.getErrorClass()));
    assertThat(sawSkeletonRow).as("skeleton-mode EXTRACTION_FAILED row written").isTrue();

    // No sourcesSucceeded in skeleton mode → terminal FAILED.
    verify(transitions)
        .finaliseTo(eq(jobId), eq(DiscoveryJobStatus.FAILED), anyString(), anyList(), anyList());
  }

  // -------- hard-constraint safety net --------

  @Test
  void run_hardConstraintFails_writesHardConstraintRow_noPersistAttempted() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate candidate =
        new DiscoveryCandidate("src_a", "https://example.test/r/1", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(candidate));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(source.fetchRecipe(candidate)).thenReturn(sampleParsedRecipe(new BigDecimal("0.9")));
    when(hardConstraintFilter.check(eq(USER_ID), anyList()))
        .thenReturn(
            new FilterResult(
                false,
                List.of(
                    new Violation(USER_ID, null, "peanuts", ViolationKind.ALLERGY, "peanuts"))));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    verify(transitions, atLeastOnce())
        .writeScrapeRow(
            argThat(
                r ->
                    r.getStatus() == ScrapeOutcome.HARD_CONSTRAINT_VIOLATION
                        && r.getSkipReason() == ScrapeSkipReason.HARD_CONSTRAINT));
    // No fingerprint dedup call after hard-constraint reject.
    verify(transitions, never()).scrapeLogExistsSince(anyString(), any());
  }

  // -------- low-confidence guard --------

  @Test
  void run_lowConfidenceParsedRecipe_writesExtractionFailedRow_lowConfidenceReason() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate candidate =
        new DiscoveryCandidate("src_a", "https://example.test/r/lc", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(candidate));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(source.fetchRecipe(candidate)).thenReturn(sampleParsedRecipe(new BigDecimal("0.3")));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    verify(transitions, atLeastOnce())
        .writeScrapeRow(
            argThat(
                r ->
                    r.getStatus() == ScrapeOutcome.EXTRACTION_FAILED
                        && r.getSkipReason() == ScrapeSkipReason.LOW_CONFIDENCE));
    verify(hardConstraintFilter, never()).check(any(), anyList());
  }

  // -------- ExtractionFailedException from source --------

  @Test
  void run_extractionFailedException_writesExtractionFailedRow_continues() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate candidate =
        new DiscoveryCandidate("src_a", "https://example.test/r/bad", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(candidate));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(source.fetchRecipe(candidate))
        .thenThrow(new ExtractionFailedException("https://example.test/r/bad", "garbage"));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    verify(transitions, atLeastOnce())
        .writeScrapeRow(
            argThat(
                r ->
                    r.getStatus() == ScrapeOutcome.EXTRACTION_FAILED
                        && "ExtractionFailedException".equals(r.getErrorClass())));
  }

  // -------- AI-filter unavailable: skip-and-flag --------

  @Test
  void run_aiFilterThrows_proceedsUnfiltered_logsWarning() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate candidate =
        new DiscoveryCandidate("src_a", "https://example.test/r/1", "T", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(candidate));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenThrow(new RuntimeException("AI down"));
    when(source.fetchRecipe(candidate)).thenReturn(sampleParsedRecipe(new BigDecimal("0.9")));
    when(hardConstraintFilter.check(eq(USER_ID), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient().when(transitions.scrapeLogExistsSince(anyString(), any())).thenReturn(false);
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    // The fetch proceeded — the source was asked to fetch.
    verify(source).fetchRecipe(candidate);
  }

  // -------- DiscoverySourceUnavailableException from source.search --------

  @Test
  void run_searchThrowsSourceUnavailable_marksSourceFailed_jobFinalisedFailed() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    when(source.search(any(DiscoveryQuery.class)))
        .thenThrow(new DiscoverySourceUnavailableException("src_a", "5xx storm", null));
    lenient()
        .when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    verify(sourceRegistry).recordFailure("src_a");
    verify(transitions)
        .finaliseTo(eq(jobId), eq(DiscoveryJobStatus.FAILED), anyString(), anyList(), anyList());
  }

  // -------- circuit-broken source skipped --------

  @Test
  void run_circuitOpen_skipsSource_addsToFailedList() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(1);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(true);
    lenient()
        .when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    runner.run(startedEvent(jobId));

    verify(source, never()).search(any());
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            anyString(),
            anyList(),
            argThat(failed -> failed.contains("src_a")));
  }

  // -------- cancellation flag short-circuits the loop --------

  @Test
  void run_cancellationFlagSet_finalisesFailedCancelled() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRequestedCount(2);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a")));

    DiscoverySource source = stubSource("src_a", Optional.empty());
    when(transitions.claim(jobId)).thenReturn(Optional.of(job));
    when(sourceRegistry.resolveEnabledByKey(anyList())).thenReturn(List.of(source));
    when(sourceRegistry.isCircuitOpen(eq(source), any())).thenReturn(false);
    when(rateLimiterRegistry.tryAcquire("src_a")).thenReturn(true);
    DiscoveryCandidate c1 =
        new DiscoveryCandidate("src_a", "https://example.test/r/1", "T1", "D", Map.of());
    DiscoveryCandidate c2 =
        new DiscoveryCandidate("src_a", "https://example.test/r/2", "T2", "D", Map.of());
    when(source.search(any(DiscoveryQuery.class))).thenReturn(List.of(c1, c2));
    when(candidateAiFilter.filter(anyList(), any(), eq(USER_ID)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    // Pre-set the cancellation flag before run() is invoked.
    runner.requestCancellation(jobId);
    runner.run(startedEvent(jobId));

    verify(source, never()).fetchRecipe(any());
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            eq("cancelled by user"),
            anyList(),
            anyList());
  }

  // -------- helpers --------

  private DiscoveryJobStartedEvent startedEvent(UUID jobId) {
    return new DiscoveryJobStartedEvent(
        jobId,
        USER_ID,
        com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
        1,
        List.of("src_a"),
        UUID.randomUUID(),
        Instant.now());
  }

  private DiscoverySource stubSource(String key, Optional<java.net.URI> robotsUri) {
    DiscoverySource src = mock(DiscoverySource.class);
    lenient().when(src.key()).thenReturn(key);
    lenient().when(src.robotsTxtUri()).thenReturn(robotsUri);
    return src;
  }

  private ParsedRecipe sampleParsedRecipe(BigDecimal confidence) {
    return new ParsedRecipe(
        "https://example.test/r/x",
        "Recipe X",
        "desc",
        List.of(
            new ParsedRecipe.ParsedIngredient("Salt", "salt", BigDecimal.ONE, "tsp", null, false)),
        List.of(new ParsedRecipe.ParsedMethodStep(1, "Mix.", null)),
        new ParsedRecipe.ParsedRecipeMetadata(2, 5, 10, 15, List.of(), "Asian", List.of("dinner")),
        "jsonld",
        confidence);
  }
}
