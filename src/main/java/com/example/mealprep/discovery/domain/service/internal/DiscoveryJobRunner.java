package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
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
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async runner for discovery jobs. Listens to {@link DiscoveryJobStartedEvent} on the {@code
 * discoveryRunnerExecutor} pool, claims the job, drives the state machine through
 * search/AI-filter/fetch phases, and finalises to a terminal status.
 *
 * <p>Per LLD §Flow 2 (lines 517-539). Per-step transactions live on {@link DiscoveryJobTransitions}
 * (a separate bean) to avoid Spring's AOP self-invocation trap; this runner method itself has NO
 * {@code @Transactional}.
 *
 * <p><strong>Cross-module hand-off (skeleton mode).</strong> The ticket's persist step should call
 * {@code RecipeWriteApi.saveImportedRecipe(userId, command)} — but that SPI method does NOT yet
 * exist (lands with {@code recipe-01l-save-imported-recipe-spi}). This implementation ships in
 * <em>skeleton mode</em>: post-hard-constraint, post-fingerprint-dedup candidates write an {@code
 * EXTRACTION_FAILED} row with {@code error_class = "saveImportedRecipeNotYetImplemented"} and the
 * job continues. The 5-minute follow-up {@code discovery-01d-real-handoff} flips the stub once
 * recipe-01l merges.
 *
 * <p>Package-private — the listener method is the only externally visible surface.
 */
@Component
public class DiscoveryJobRunner {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryJobRunner.class);

  private final DiscoveryJobRepository jobRepository;
  private final DiscoverySourceRepository sourceRepository;
  private final SourceRegistry sourceRegistry;
  private final RobotsTxtGate robotsTxtGate;
  private final SourceRateLimiterRegistry rateLimiterRegistry;
  private final ContentFingerprintHasher fingerprintHasher;
  private final CandidateAiFilter candidateAiFilter;
  private final HardConstraintFilterService hardConstraintFilter;
  private final DiscoveryJobTransitions transitions;
  private final ApplicationEventPublisher eventPublisher;
  private final DiscoveryProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * In-memory cancellation flags. Written by the controller thread via {@link
   * #requestCancellation(UUID)}, read by the runner thread on every fetch-loop iteration. {@code
   * ConcurrentHashMap} + {@code AtomicBoolean} for thread-safety; cleared in the runner's {@code
   * finally} block to prevent unbounded growth.
   */
  private final Map<UUID, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();

  /**
   * Per-job sync waiters keyed by {@code jobId}. {@code runJobSync} (01f) registers a {@link
   * CompletableFuture} BEFORE {@code startJob}'s tx commits; the runner completes it with the
   * terminal status the moment {@code finalise}/{@code finaliseCrashed}/{@code sweepOrphans}
   * transitions the job. The sync caller blocks on {@code future.get(timeout)} rather than polling.
   *
   * <p>{@code ConcurrentHashMap} for thread-safety: the controller thread registers; the runner (or
   * orphan-sweep / crash) thread completes. {@link CompletableFuture#complete} is a one-shot — a
   * second completion attempt is a harmless no-op.
   */
  private final Map<UUID, CompletableFuture<DiscoveryJobStatus>> syncWaiters =
      new ConcurrentHashMap<>();

  public DiscoveryJobRunner(
      DiscoveryJobRepository jobRepository,
      DiscoverySourceRepository sourceRepository,
      SourceRegistry sourceRegistry,
      RobotsTxtGate robotsTxtGate,
      SourceRateLimiterRegistry rateLimiterRegistry,
      ContentFingerprintHasher fingerprintHasher,
      CandidateAiFilter candidateAiFilter,
      HardConstraintFilterService hardConstraintFilter,
      DiscoveryJobTransitions transitions,
      ApplicationEventPublisher eventPublisher,
      DiscoveryProperties properties,
      ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.sourceRepository = sourceRepository;
    this.sourceRegistry = sourceRegistry;
    this.robotsTxtGate = robotsTxtGate;
    this.rateLimiterRegistry = rateLimiterRegistry;
    this.fingerprintHasher = fingerprintHasher;
    this.candidateAiFilter = candidateAiFilter;
    this.hardConstraintFilter = hardConstraintFilter;
    this.transitions = transitions;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  // ===== Listener =====

  /**
   * The single async entry-point. Per ticket invariants 1-3 + LLD line 567:
   *
   * <ul>
   *   <li>{@code @Async("discoveryRunnerExecutor")} — runs on the bounded pool from 01a's {@link
   *       com.example.mealprep.discovery.config.DiscoveryAsyncConfig}.
   *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — fires only after {@code startJob}'s
   *       publishing tx commits, so the job row is durably visible.
   *   <li>NO {@code @Transactional} on this method — each runner step opens its own short tx via
   *       {@link DiscoveryJobTransitions}.
   * </ul>
   */
  @Async("discoveryRunnerExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void run(DiscoveryJobStartedEvent event) {
    UUID jobId = event.jobId();
    log.info(
        "discovery job {} started (trigger={}, sources={})",
        jobId,
        event.trigger(),
        event.sourcesRequested());
    try {
      executeJob(jobId);
    } catch (Throwable t) {
      log.error("discovery job {} runner crashed", jobId, t);
      finaliseCrashed(jobId, t);
    } finally {
      cancellationRequests.remove(jobId);
    }
  }

  // ===== Public surface for DiscoveryServiceImpl =====

  /**
   * Set the cancellation flag for {@code jobId}. Called by {@code DiscoveryServiceImpl.cancelJob}
   * when the job is currently {@code RUNNING}; the runner sees the flag at its next per-candidate
   * iteration and finalises early.
   */
  public void requestCancellation(UUID jobId) {
    cancellationRequests.computeIfAbsent(jobId, k -> new AtomicBoolean(false)).set(true);
  }

  /**
   * Register a sync waiter for {@code jobId}. Called by {@code DiscoveryServiceImpl.runJobSync}
   * immediately after {@code startJob} returns the QUEUED DTO — the registration is in-memory and
   * happens BEFORE the AFTER_COMMIT {@code DiscoveryJobStartedEvent} listener can fire, so the
   * runner's terminal path always finds the waiter (LLD line 385).
   */
  public void registerSyncWaiter(UUID jobId, CompletableFuture<DiscoveryJobStatus> waiter) {
    syncWaiters.put(jobId, waiter);
  }

  /**
   * Remove the sync waiter for {@code jobId}. Called by {@code runJobSync} in a {@code finally}
   * block so a timed-out call never leaks a map entry.
   */
  public void unregisterSyncWaiter(UUID jobId) {
    syncWaiters.remove(jobId);
  }

  /** Test-only: current sync-waiter map size, for hygiene assertions in ITs. */
  int syncWaiterCount() {
    return syncWaiters.size();
  }

  /**
   * Complete (and remove) the sync waiter for {@code jobId} with the job's terminal status, if a
   * sync caller is blocked on it. No-op when no waiter is registered (async path). Invoked from
   * every terminal-transition path: {@code finalise}, the cancellation branch, {@code
   * finaliseCrashed}, and the orphan sweep.
   */
  private void completeSyncWaiter(UUID jobId, DiscoveryJobStatus terminalStatus) {
    CompletableFuture<DiscoveryJobStatus> waiter = syncWaiters.remove(jobId);
    if (waiter != null) {
      waiter.complete(terminalStatus);
    }
  }

  /**
   * Invokable on-demand variant of the orphan sweep. Same body as the {@code @Scheduled}
   * counterpart. Used by {@code DiscoveryServiceImpl.runOrphanSweep} for the admin endpoint.
   */
  public OrphanSweepResultDto sweepOrphansNow() {
    return doSweepOrphans();
  }

  // ===== Scheduled sweep =====

  /**
   * Periodic orphan sweep. Finalises {@code RUNNING} jobs whose {@code startedAt} predates the
   * heartbeat window (default 10 min, configurable via {@code DiscoveryProperties}). Per LLD lines
   * 549-551.
   *
   * <p>{@code initialDelay} avoids running at boot before app readiness; {@code fixedDelay} (not
   * {@code fixedRate}) waits for the previous invocation to finish.
   */
  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
  void sweepOrphans() {
    OrphanSweepResultDto result = doSweepOrphans();
    if (result.resumedCount() > 0) {
      log.info("orphan sweep resumed {} job(s)", result.resumedCount());
    }
  }

  // ===== Inner mechanics =====

  private void executeJob(UUID jobId) {
    Optional<DiscoveryJob> claimed = transitions.claim(jobId);
    if (claimed.isEmpty()) {
      // Either non-existent, non-QUEUED, or lost a claim race. Already logged in claim().
      return;
    }
    DiscoveryJob job = claimed.get();

    DiscoveryConstraints constraints = readConstraints(job);
    if (constraints == null) {
      log.warn("discovery job {} has invalid constraintsJson; finalising FAILED", jobId);
      finalise(
          jobId,
          DiscoveryJobStatus.FAILED,
          "constraintsJson could not be deserialised",
          Collections.emptyList(),
          Collections.emptyList(),
          job);
      return;
    }

    List<DiscoverySource> requestedBeans = resolveRequestedSources(job);
    List<String> sourcesFailed = new ArrayList<>();
    List<String> sourcesSucceeded = new ArrayList<>();
    List<DiscoverySource> active = filterCircuitBroken(requestedBeans, sourcesFailed);

    // === Search phase ===
    SearchPhaseOutcome searchOutcome = searchPhase(job, active, constraints, sourcesFailed);
    transitions.recordCandidatesSeen(jobId, searchOutcome.candidates.size());

    // === AI-filter phase ===
    List<DiscoveryCandidate> filtered = aiFilterPhase(job, searchOutcome.candidates, constraints);
    transitions.recordCandidatesAfterFilter(jobId, filtered.size());

    // === Fetch phase ===
    fetchPhase(
        jobId,
        job.getUserId(),
        job.getTraceId(),
        job.getRequestedCount(),
        constraints,
        active,
        filtered,
        sourcesSucceeded,
        sourcesFailed);

    // === Finalise ===
    finaliseTerminal(
        jobId, job.getUserId(), job.getTraceId(), requestedBeans, sourcesSucceeded, sourcesFailed);
  }

  private DiscoveryConstraints readConstraints(DiscoveryJob job) {
    if (job.getConstraintsJson() == null || job.getConstraintsJson() instanceof NullNode) {
      return null;
    }
    try {
      return objectMapper.treeToValue(job.getConstraintsJson(), DiscoveryConstraints.class);
    } catch (JsonProcessingException ex) {
      log.warn("could not deserialise constraintsJson for job {}", job.getId(), ex);
      return null;
    }
  }

  private List<DiscoverySource> resolveRequestedSources(DiscoveryJob job) {
    List<String> keys =
        job.getSourcesRequested() == null ? Collections.emptyList() : job.getSourcesRequested();
    return sourceRegistry.resolveEnabledByKey(keys);
  }

  private List<DiscoverySource> filterCircuitBroken(
      List<DiscoverySource> beans, List<String> sourcesFailedAccumulator) {
    Instant now = Instant.now();
    List<DiscoverySource> active = new ArrayList<>(beans.size());
    for (DiscoverySource bean : beans) {
      if (sourceRegistry.isCircuitOpen(bean, now)) {
        log.info("source {} circuit-breaker open; skipping for this job (cooldown 1h)", bean.key());
        if (!sourcesFailedAccumulator.contains(bean.key())) {
          sourcesFailedAccumulator.add(bean.key());
        }
      } else {
        active.add(bean);
      }
    }
    return active;
  }

  // ----- Phase: search -----

  private static final class SearchPhaseOutcome {
    final List<DiscoveryCandidate> candidates;

    SearchPhaseOutcome(List<DiscoveryCandidate> candidates) {
      this.candidates = candidates;
    }
  }

  private SearchPhaseOutcome searchPhase(
      DiscoveryJob job,
      List<DiscoverySource> active,
      DiscoveryConstraints constraints,
      List<String> sourcesFailed) {
    List<DiscoveryCandidate> merged = new ArrayList<>();
    Integer cap = constraints.maxRecipesPerSource();
    int perSourceCap = cap == null ? 20 : Math.max(1, cap);

    for (DiscoverySource source : active) {
      String sourceKey = source.key();
      String userAgent = userAgentFor(sourceKey);
      DiscoveryQuery query = new DiscoveryQuery(constraints, perSourceCap, userAgent);

      if (!rateLimiterRegistry.tryAcquire(sourceKey)) {
        writeScrapeRow(
            scrapeRowBuilder(job.getId(), sourceKey, sourceKey + ":search")
                .status(ScrapeOutcome.RATE_LIMITED)
                .robotsTxtOutcome(RobotsTxtOutcome.SKIPPED)
                .skipReason(ScrapeSkipReason.RATE_LIMITED)
                .occurredAt(Instant.now())
                .build());
        if (!sourcesFailed.contains(sourceKey)) {
          sourcesFailed.add(sourceKey);
        }
        continue;
      }

      try {
        Instant start = Instant.now();
        List<DiscoveryCandidate> raw = source.search(query);
        int latencyMs = (int) Duration.between(start, Instant.now()).toMillis();
        log.debug("source {} returned {} candidate(s) in {}ms", sourceKey, raw.size(), latencyMs);
        // Cap per source per LLD line 253.
        List<DiscoveryCandidate> capped =
            raw.size() > perSourceCap ? raw.subList(0, perSourceCap) : raw;
        merged.addAll(capped);
      } catch (DiscoverySourceUnavailableException ex) {
        log.warn("source {} unavailable: {}", sourceKey, ex.getMessage());
        sourceRegistry.recordFailure(sourceKey);
        if (!sourcesFailed.contains(sourceKey)) {
          sourcesFailed.add(sourceKey);
        }
        writeScrapeRow(
            scrapeRowBuilder(job.getId(), sourceKey, sourceKey + ":search")
                .status(ScrapeOutcome.HTTP_ERROR)
                .robotsTxtOutcome(RobotsTxtOutcome.SKIPPED)
                .errorClass(ex.getClass().getSimpleName())
                .errorMessage(ex.getMessage())
                .occurredAt(Instant.now())
                .build());
      } catch (RuntimeException ex) {
        log.warn("source {} search threw {}: {}", sourceKey, ex.getClass(), ex.getMessage());
        sourceRegistry.recordFailure(sourceKey);
        if (!sourcesFailed.contains(sourceKey)) {
          sourcesFailed.add(sourceKey);
        }
        writeScrapeRow(
            scrapeRowBuilder(job.getId(), sourceKey, sourceKey + ":search")
                .status(ScrapeOutcome.HTTP_ERROR)
                .robotsTxtOutcome(RobotsTxtOutcome.SKIPPED)
                .errorClass(ex.getClass().getSimpleName())
                .errorMessage(ex.getMessage())
                .occurredAt(Instant.now())
                .build());
      }
    }
    return new SearchPhaseOutcome(merged);
  }

  private String userAgentFor(String sourceKey) {
    return sourceRepository
        .findBySourceKey(sourceKey)
        .map(com.example.mealprep.discovery.domain.entity.DiscoverySource::getUserAgent)
        .orElse("MealPrepAI/1.0");
  }

  // ----- Phase: AI filter -----

  private List<DiscoveryCandidate> aiFilterPhase(
      DiscoveryJob job, List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints) {
    if (candidates.isEmpty()) {
      return candidates;
    }
    try {
      return candidateAiFilter.filter(candidates, constraints, job.getUserId());
    } catch (RuntimeException ex) {
      // Skip-and-flag per LLD line 573 — proceed unfiltered.
      log.warn(
          "AI candidate filter unavailable for job {}: {}; proceeding unfiltered",
          job.getId(),
          ex.getMessage());
      return candidates;
    }
  }

  // ----- Phase: fetch -----

  private void fetchPhase(
      UUID jobId,
      UUID userId,
      UUID traceId,
      int requestedCount,
      DiscoveryConstraints constraints,
      List<DiscoverySource> active,
      List<DiscoveryCandidate> candidates,
      List<String> sourcesSucceeded,
      List<String> sourcesFailed) {
    Map<String, DiscoverySource> bySourceKey = new java.util.HashMap<>();
    for (DiscoverySource bean : active) {
      bySourceKey.put(bean.key(), bean);
    }

    int ingested = 0;
    for (DiscoveryCandidate candidate : candidates) {
      // Cancellation check (per-iteration) per ticket invariant 17.
      AtomicBoolean cancelFlag = cancellationRequests.get(jobId);
      if (cancelFlag != null && cancelFlag.get()) {
        log.info("discovery job {} cancelled by user; finalising FAILED", jobId);
        transitions.finaliseTo(
            jobId, DiscoveryJobStatus.FAILED, "cancelled by user", sourcesSucceeded, sourcesFailed);
        completeSyncWaiter(jobId, DiscoveryJobStatus.FAILED);
        publishJobCompleted(jobId, userId, traceId);
        return;
      }

      // Quota stop per ticket invariant 20 + 26.
      if (ingested >= requestedCount) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, candidate.sourceKey(), candidate.candidateUrl())
                .status(ScrapeOutcome.SKIPPED)
                .robotsTxtOutcome(RobotsTxtOutcome.SKIPPED)
                .skipReason(ScrapeSkipReason.JOB_QUOTA_REACHED)
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      DiscoverySource source = bySourceKey.get(candidate.sourceKey());
      if (source == null) {
        log.warn(
            "candidate {} references unknown source key '{}' — skipping",
            candidate.candidateUrl(),
            candidate.sourceKey());
        continue;
      }

      Instant fetchStart = Instant.now();

      // Robots check.
      RobotsTxtOutcome robotsOutcome = checkRobots(source, candidate);
      if (robotsOutcome == RobotsTxtOutcome.DISALLOWED) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .status(ScrapeOutcome.ROBOTS_DISALLOWED)
                .robotsTxtOutcome(RobotsTxtOutcome.DISALLOWED)
                .skipReason(ScrapeSkipReason.ROBOTS_DISALLOWED)
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }
      if (robotsOutcome == RobotsTxtOutcome.UNAVAILABLE && respectsRobots(source.key())) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .status(ScrapeOutcome.SKIPPED)
                .robotsTxtOutcome(RobotsTxtOutcome.UNAVAILABLE)
                .skipReason(ScrapeSkipReason.ROBOTS_DISALLOWED)
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      // Rate-limit check.
      if (!rateLimiterRegistry.tryAcquire(source.key())) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .status(ScrapeOutcome.RATE_LIMITED)
                .robotsTxtOutcome(robotsOutcome)
                .skipReason(ScrapeSkipReason.RATE_LIMITED)
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      // Fetch.
      ParsedRecipe parsed;
      try {
        parsed = source.fetchRecipe(candidate);
      } catch (ExtractionFailedException ex) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .status(ScrapeOutcome.EXTRACTION_FAILED)
                .robotsTxtOutcome(robotsOutcome)
                .errorClass(ex.getClass().getSimpleName())
                .errorMessage(ex.getMessage())
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      } catch (RuntimeException ex) {
        log.warn(
            "fetchRecipe {} threw {}: {}",
            candidate.candidateUrl(),
            ex.getClass().getSimpleName(),
            ex.getMessage());
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .status(ScrapeOutcome.EXTRACTION_FAILED)
                .robotsTxtOutcome(robotsOutcome)
                .errorClass(ex.getClass().getSimpleName())
                .errorMessage(ex.getMessage())
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      // Low-confidence guard per ticket invariant 21.
      if (parsed.extractionConfidence() != null
          && parsed.extractionConfidence().compareTo(new BigDecimal("0.5")) < 0) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .canonicalUrl(parsed.canonicalUrl())
                .status(ScrapeOutcome.EXTRACTION_FAILED)
                .robotsTxtOutcome(robotsOutcome)
                .skipReason(ScrapeSkipReason.LOW_CONFIDENCE)
                .extractionMethod(parsed.extractionMethod())
                .extractionConfidence(parsed.extractionConfidence())
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      // Hard-constraint filter — the deterministic safety net (LLD line 528).
      List<String> ingredientKeys =
          parsed.ingredients() == null
              ? Collections.emptyList()
              : parsed.ingredients().stream()
                  .map(ParsedRecipe.ParsedIngredient::ingredientMappingKey)
                  .filter(Objects::nonNull)
                  .toList();
      FilterResult hardCheck = hardConstraintFilter.check(userId, ingredientKeys);
      if (!hardCheck.passes()) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .canonicalUrl(parsed.canonicalUrl())
                .status(ScrapeOutcome.HARD_CONSTRAINT_VIOLATION)
                .robotsTxtOutcome(robotsOutcome)
                .skipReason(ScrapeSkipReason.HARD_CONSTRAINT)
                .extractionMethod(parsed.extractionMethod())
                .extractionConfidence(parsed.extractionConfidence())
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        continue;
      }

      // Content-fingerprint dedup per ticket invariant 23.
      String fingerprint = fingerprintHasher.fingerprint(parsed);
      Instant cutoff = Instant.now().minus(Duration.ofDays(properties.duplicateLookbackDays()));
      if (transitions.scrapeLogExistsSince(fingerprint, cutoff)) {
        writeScrapeRow(
            scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
                .canonicalUrl(parsed.canonicalUrl())
                .status(ScrapeOutcome.DUPLICATE)
                .robotsTxtOutcome(robotsOutcome)
                .skipReason(ScrapeSkipReason.DUPLICATE)
                .contentFingerprint(fingerprint)
                .extractionMethod(parsed.extractionMethod())
                .extractionConfidence(parsed.extractionConfidence())
                .latencyMs(latencyMs(fetchStart))
                .occurredAt(Instant.now())
                .build());
        transitions.incrementSkippedDuplicate(jobId);
        continue;
      }

      // === Persist via RecipeWriteApi.saveImportedRecipe ===
      // SKELETON MODE: the SPI method does not yet exist (recipe-01l). Write an EXTRACTION_FAILED
      // row carrying the gap reason so the row is auditable and the job continues. The
      // 5-minute follow-up (discovery-01d-real-handoff) replaces this block with the real call.
      writeScrapeRow(
          scrapeRowBuilder(jobId, source.key(), candidate.candidateUrl())
              .canonicalUrl(parsed.canonicalUrl())
              .status(ScrapeOutcome.EXTRACTION_FAILED)
              .robotsTxtOutcome(robotsOutcome)
              .contentFingerprint(fingerprint)
              .extractionMethod(parsed.extractionMethod())
              .extractionConfidence(parsed.extractionConfidence())
              .errorClass("saveImportedRecipeNotYetImplemented")
              .errorMessage(
                  "RecipeWriteApi.saveImportedRecipe SPI ships in recipe-01l; skeleton row.")
              .latencyMs(latencyMs(fetchStart))
              .occurredAt(Instant.now())
              .build());
      // In skeleton mode we do NOT increment ingested or mark source as succeeded — there is
      // no recipe to credit. The job's terminal state reflects the gap (FAILED unless any
      // source's search succeeded, in which case PARTIAL via succeeded-by-search bookkeeping
      // below at finaliseTerminal; ingested remains 0).
      // The full happy-path is documented for reference:
      //   UUID recipeId = recipeWriteApi.saveImportedRecipe(userId, command);
      //   writeScrapeRow(... SUCCESS, recipeId, contentFingerprint ...);
      //   transitions.incrementIngested(jobId);
      //   sourceRegistry.recordSuccess(source.key());
      //   if (!sourcesSucceeded.contains(source.key())) sourcesSucceeded.add(source.key());
      //   eventPublisher.publishEvent(new DiscoveryRecipeIngestedEvent(...));
      //   ingested++;
    }
  }

  private RobotsTxtOutcome checkRobots(DiscoverySource source, DiscoveryCandidate candidate) {
    Optional<URI> robotsUri = source.robotsTxtUri();
    if (robotsUri.isEmpty()) {
      return RobotsTxtOutcome.SKIPPED;
    }
    String userAgent = userAgentFor(source.key());
    try {
      return robotsTxtGate.check(URI.create(candidate.candidateUrl()), userAgent);
    } catch (RuntimeException ex) {
      log.warn(
          "robots.txt check for {} threw {}: {} — treating as UNAVAILABLE",
          candidate.candidateUrl(),
          ex.getClass().getSimpleName(),
          ex.getMessage());
      return RobotsTxtOutcome.UNAVAILABLE;
    }
  }

  private boolean respectsRobots(String sourceKey) {
    return sourceRepository
        .findBySourceKey(sourceKey)
        .map(com.example.mealprep.discovery.domain.entity.DiscoverySource::isRespectRobotsTxt)
        .orElse(true);
  }

  // ----- Finalise -----

  private void finaliseTerminal(
      UUID jobId,
      UUID userId,
      UUID traceId,
      List<DiscoverySource> requestedBeans,
      List<String> sourcesSucceeded,
      List<String> sourcesFailed) {
    DiscoveryJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null) {
      return;
    }
    DiscoveryJobStatus terminal;
    String errorSummary;
    int recipesIngested = job.getRecipesIngested();

    if (!sourcesSucceeded.isEmpty()
        && sourcesFailed.isEmpty()
        && recipesIngested >= job.getRequestedCount()) {
      terminal = DiscoveryJobStatus.SUCCEEDED;
      errorSummary = null;
    } else if (!sourcesSucceeded.isEmpty()) {
      terminal = DiscoveryJobStatus.PARTIAL;
      errorSummary =
          sourcesFailed.isEmpty()
              ? "partial: quota not met (ingested=" + recipesIngested + ")"
              : "partial: failed sources=" + String.join(",", sourcesFailed);
    } else {
      terminal = DiscoveryJobStatus.FAILED;
      errorSummary =
          sourcesFailed.isEmpty()
              ? "no source produced an ingest"
              : "all sources failed: " + String.join(",", sourcesFailed);
    }
    if (finalise(jobId, terminal, errorSummary, sourcesSucceeded, sourcesFailed, job)) {
      publishJobCompleted(jobId, userId, traceId);
    }
  }

  /**
   * Wraps {@link DiscoveryJobTransitions#finaliseTo} with the runner-side side effects (sync-waiter
   * completion + log). Returns {@code true} when the transition actually happened — i.e. the job
   * was non-terminal and the row was written. Returns {@code false} when the terminal-state guard
   * short-circuited (the job was already in a terminal state from an earlier finalise on this run,
   * e.g. the {@code fetchPhase} cancellation branch). Callers MUST skip downstream effects (event
   * publish) on a {@code false} return to avoid double-firing.
   */
  private boolean finalise(
      UUID jobId,
      DiscoveryJobStatus terminal,
      String errorSummary,
      List<String> sourcesSucceeded,
      List<String> sourcesFailed,
      DiscoveryJob job) {
    Optional<DiscoveryJob> result =
        transitions.finaliseTo(jobId, terminal, errorSummary, sourcesSucceeded, sourcesFailed);
    if (result.isEmpty()) {
      // Terminal-state guard tripped — an earlier path already finalised this job. Do NOT
      // complete the sync waiter or log a second "finalised" line — both have already happened.
      return false;
    }
    // Unblock any sync caller (01f). AFTER the DB write, BEFORE the completed-event publish (the
    // publish happens in finaliseTerminal right after this returns) per ticket invariant 11.
    completeSyncWaiter(jobId, terminal);
    log.info("discovery job {} finalised {} ({})", jobId, terminal, errorSummary);
    return true;
  }

  private void finaliseCrashed(UUID jobId, Throwable cause) {
    try {
      DiscoveryJob job = jobRepository.findById(jobId).orElse(null);
      if (job == null) {
        return;
      }
      Optional<DiscoveryJob> result =
          transitions.finaliseTo(
              jobId,
              DiscoveryJobStatus.FAILED,
              "runner crashed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
              job.getSourcesSucceeded(),
              job.getSourcesFailed());
      if (result.isEmpty()) {
        // Terminal-state guard tripped — the job was already finalised (e.g. cancellation
        // fast-path ran, then a downstream throw triggered the outer catch). Don't double-fire.
        return;
      }
      completeSyncWaiter(jobId, DiscoveryJobStatus.FAILED);
      publishJobCompleted(jobId, job.getUserId(), job.getTraceId());
    } catch (RuntimeException secondary) {
      log.error("failed to finalise crashed job {} — leaving for orphan sweep", jobId, secondary);
    }
  }

  private void publishJobCompleted(UUID jobId, UUID userId, UUID traceId) {
    DiscoveryJob refreshed = jobRepository.findById(jobId).orElse(null);
    if (refreshed == null) {
      return;
    }
    eventPublisher.publishEvent(
        new DiscoveryJobCompletedEvent(
            jobId,
            userId,
            refreshed.getStatus(),
            refreshed.getRecipesIngested(),
            refreshed.getCandidatesSeen(),
            List.copyOf(
                refreshed.getSourcesSucceeded() == null
                    ? Collections.emptyList()
                    : refreshed.getSourcesSucceeded()),
            List.copyOf(
                refreshed.getSourcesFailed() == null
                    ? Collections.emptyList()
                    : refreshed.getSourcesFailed()),
            refreshed.getErrorSummary(),
            traceId,
            Instant.now()));
  }

  // ----- Orphan sweep -----

  private OrphanSweepResultDto doSweepOrphans() {
    Duration heartbeat = properties.heartbeatTimeout();
    Instant cutoff = Instant.now().minus(heartbeat);
    List<DiscoveryJob> orphans = jobRepository.findOrphanRunning(cutoff);
    int resumed = 0;
    for (DiscoveryJob job : orphans) {
      Optional<DiscoveryJob> finalised =
          transitions.finaliseTo(
              job.getId(),
              DiscoveryJobStatus.FAILED,
              "runner crashed; resumed by sweep",
              job.getSourcesSucceeded() == null
                  ? Collections.emptyList()
                  : job.getSourcesSucceeded(),
              job.getSourcesFailed() == null ? Collections.emptyList() : job.getSourcesFailed());
      if (finalised.isPresent()) {
        resumed++;
        // A sync caller that timed out already returned; but if it is still blocked (e.g. a slow
        // orphan crossed the heartbeat window while the caller waits) unblock it now (invariant
        // 13).
        completeSyncWaiter(job.getId(), DiscoveryJobStatus.FAILED);
        publishJobCompleted(job.getId(), job.getUserId(), job.getTraceId());
      }
    }
    return new OrphanSweepResultDto(resumed);
  }

  // ----- Helpers -----

  private int latencyMs(Instant start) {
    return (int) Math.max(0, Duration.between(start, Instant.now()).toMillis());
  }

  private void writeScrapeRow(DiscoveryScrapeLog row) {
    transitions.writeScrapeRow(row);
  }

  private DiscoveryScrapeLog.DiscoveryScrapeLogBuilder scrapeRowBuilder(
      UUID jobId, String sourceKey, String candidateUrl) {
    return DiscoveryScrapeLog.builder()
        .id(UUID.randomUUID())
        .jobId(jobId)
        .sourceKey(sourceKey)
        .candidateUrl(truncate(candidateUrl, 2048));
  }

  private String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }
}
