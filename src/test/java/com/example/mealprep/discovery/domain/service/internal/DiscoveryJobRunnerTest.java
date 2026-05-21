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
import static org.mockito.Mockito.times;
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
 * <p>discovery-01g landed the real {@code RecipeWriteApi.saveImportedRecipe} SPI: the persist step
 * now writes a {@code SUCCESS} scrape row + bumps {@code recipes_ingested} on the happy path. The
 * SPI is mocked here; an end-to-end Testcontainers IT lives in {@code DiscoveryRunnerPhasesIT}.
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
  @Mock private RecipeWriteApi recipeWriteApi;

  private DiscoveryJobRunner runner;

  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
    // discovery-01g: default-stub the SPI to a "newly created" result so the happy-path branch
    // emits a SUCCESS scrape row. Individual tests override with .thenReturn / .thenThrow as
    // needed.
    lenient()
        .when(recipeWriteApi.saveImportedRecipe(any(ImportedRecipeData.class)))
        .thenAnswer(
            inv -> new ImportedRecipeResult(UUID.randomUUID(), UUID.randomUUID(), true, null));
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

  // -------- happy path (discovery-01g): SPI persists → SUCCESS row, ingested bumped --------

  @Test
  void run_oneSourceOneCandidate_realSpi_writesSuccessRow_jobSucceeds() {
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
    boolean sawSuccessRow =
        rows.getAllValues().stream()
            .anyMatch(r -> r.getStatus() == ScrapeOutcome.SUCCESS && r.getRecipeId() != null);
    assertThat(sawSuccessRow).as("SUCCESS scrape row written with recipe id").isTrue();
    verify(transitions, atLeastOnce()).incrementIngested(jobId);
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
    // Bug fix verification: prior to the terminal-state guard in DiscoveryJobTransitions.finaliseTo
    // (+ the Optional-return gating in DiscoveryJobRunner.finalise/finaliseCrashed), a cancelled
    // job was finalised TWICE — once with "cancelled by user" from the fetchPhase fast-path, then
    // a second time with "no source produced an ingest" from finaliseTerminal — overwriting the
    // audit-trail errorSummary and firing two DiscoveryJobCompletedEvents. The old test passed
    // only because verify(...).finaliseTo(eq("cancelled by user")) silently ignored the second
    // wrong-arg call (Mockito default times(1) matches against arg-equality, not total mock
    // invocations). The fix lives in DiscoveryJobTransitions.finaliseTo (terminal-state guard
    // returning Optional.empty()) and DiscoveryJobRunner.finalise (gates completeSyncWaiter +
    // publishJobCompleted on the Optional being non-empty).
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

    // Stub the transitions mock to mirror the real terminal-state guard contract: the FIRST
    // call (cancellation) actually transitions the job and returns a populated Optional; any
    // SUBSEQUENT call (e.g. from finaliseTerminal falling through executeJob) returns empty,
    // signalling "no transition occurred". The real DiscoveryJobTransitions.finaliseTo does
    // exactly this (covered by DiscoveryJobTransitionsTest#finaliseTo_alreadyTerminal_isNoop).
    when(transitions.finaliseTo(eq(jobId), any(), any(), anyList(), anyList()))
        .thenReturn(Optional.of(job)) // cancellation call: real transition
        .thenReturn(Optional.empty()); // any subsequent call: guard trips, no-op

    // Pre-set the cancellation flag before run() is invoked.
    runner.requestCancellation(jobId);
    runner.run(startedEvent(jobId));

    verify(source, never()).fetchRecipe(any());

    // The cancellation fast-path's finaliseTo MUST have been invoked with "cancelled by user".
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            eq("cancelled by user"),
            anyList(),
            anyList());

    // The runner-side gating means the completion event fires EXACTLY ONCE — from the
    // cancellation branch. The second pass through finaliseTerminal must be a no-op publish
    // because the terminal-state guard returned Optional.empty(). This is the killing assertion
    // for the double-publish bug.
    verify(eventPublisher, times(1)).publishEvent(any(DiscoveryJobCompletedEvent.class));

    // The runner-side correctness gate: even with the cancellation branch having returned
    // out of fetchPhase, the post-fetchPhase finaliseTerminal call sees an empty Optional from
    // the (now-guarded) transitions.finaliseTo and does NOT publish a second completion event.
    // The errorSummary preservation is pinned in DiscoveryJobTransitionsTest where the real
    // guard runs against a populated job — here at the runner boundary, we pin the
    // "exactly-once event publish" half of the bug.
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
