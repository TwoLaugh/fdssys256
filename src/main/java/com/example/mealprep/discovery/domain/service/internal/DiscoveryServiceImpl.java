package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
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
import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobAlreadyTerminalException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link DiscoveryService} and {@link DiscoveryQueryService} per the
 * style-guide rule ("one impl, both interfaces"). Replaces the 01a {@code DiscoveryServiceStub}.
 *
 * <p>01b scope (ticket invariants 1-23):
 *
 * <ul>
 *   <li>{@code startJob} persists the QUEUED job and publishes {@code DiscoveryJobStartedEvent}
 *       AFTER_COMMIT for the (01d) runner.
 *   <li>{@code cancelJob} flips {@code QUEUED → FAILED}; terminal states + the temporary RUNNING
 *       branch throw {@link DiscoveryJobAlreadyTerminalException}.
 *   <li>Query methods cover the user/admin read surface; scrape-log fetch pre-checks the job
 *       exists.
 *   <li>{@code enableSource} / {@code disableSource} flip the {@code enabled} flag; {@code
 *       runOrphanSweep} is a placeholder returning {@code 0} until 01d.
 *   <li>{@code runJobSync} throws {@link UnsupportedOperationException} — ships with 01f.
 * </ul>
 */
@Service
public class DiscoveryServiceImpl implements DiscoveryService, DiscoveryQueryService {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryServiceImpl.class);

  private static final EnumSet<DiscoveryJobStatus> TERMINAL_STATES =
      EnumSet.of(
          DiscoveryJobStatus.SUCCEEDED, DiscoveryJobStatus.FAILED, DiscoveryJobStatus.PARTIAL);

  private final DiscoveryJobRepository jobRepository;
  private final DiscoverySourceRepository sourceRepository;
  private final DiscoveryScrapeLogRepository scrapeLogRepository;
  private final DiscoveryJobMapper jobMapper;
  private final DiscoverySourceMapper sourceMapper;
  private final DiscoveryScrapeLogMapper scrapeLogMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final DiscoveryJobRunner runner;
  private final DiscoveryProperties properties;

  // Self-proxy: runJobSync (non-transactional, blocks on the sync waiter) calls startJob, which is
  // @Transactional and publishes DiscoveryJobStartedEvent AFTER_COMMIT. A plain this.startJob() is
  // a Spring self-invocation — the proxy is bypassed, startJob runs tx-less, the AFTER_COMMIT
  // event is silently dropped, the runner never processes the job, and runJobSync times out
  // returning a QUEUED DTO (round-5 self-invocation + round-3 AFTER_COMMIT-drop, compounded).
  // Route the call through the injected proxy so startJob's tx boundary applies; @Lazy breaks
  // the constructor cycle. Hand-constructed unit tests leave `self` null -> fall back to `this`.
  @Autowired @Lazy private DiscoveryServiceImpl self;

  // runJobSync's final re-read must reflect the runner's cross-thread terminal UPDATE. The runner
  // commits RUNNING/FAILED on its own thread+EM; this request thread loaded the QUEUED row in
  // startJobWithId. Production runs spring.jpa.open-in-view=false (fresh EM per repo call → fresh
  // read), but the test classpath's application.properties shadows main and does NOT mirror that
  // key, so OSIV is ON under @SpringBootTest: one EM is bound to the request thread for the whole
  // request, its L1 still holds the stale QUEUED entity, and findById returns it instead of
  // hitting the DB. Clearing the persistence context before the re-read makes the read correct
  // regardless of the OSIV setting (wave-2 retro: test props shadow main; wave-3 addendum:
  // cross-thread async update + same-request re-read needs an explicit L1 evict). Hand-constructed
  // unit tests inject no EM → null → guarded.
  @PersistenceContext private EntityManager entityManager;

  public DiscoveryServiceImpl(
      DiscoveryJobRepository jobRepository,
      DiscoverySourceRepository sourceRepository,
      DiscoveryScrapeLogRepository scrapeLogRepository,
      DiscoveryJobMapper jobMapper,
      DiscoverySourceMapper sourceMapper,
      DiscoveryScrapeLogMapper scrapeLogMapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      DiscoveryJobRunner runner,
      DiscoveryProperties properties) {
    this.jobRepository = jobRepository;
    this.sourceRepository = sourceRepository;
    this.scrapeLogRepository = scrapeLogRepository;
    this.jobMapper = jobMapper;
    this.sourceMapper = sourceMapper;
    this.scrapeLogMapper = scrapeLogMapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.runner = runner;
    this.properties = properties;
  }

  // ===== DiscoveryService — update path =====

  @Override
  @Transactional
  public DiscoveryJobDto startJob(UUID userId, StartDiscoveryJobRequest request) {
    return startJobWithId(userId, request, UUID.randomUUID());
  }

  /**
   * Body of {@link #startJob} parameterised by a caller-supplied job id. {@code runJobSync} needs
   * the id BEFORE the {@code DiscoveryJobStartedEvent} is published so it can register the sync
   * waiter ahead of the {@code @Async} AFTER_COMMIT runner — otherwise the runner can finalise the
   * job and call {@code completeSyncWaiter} before the waiter exists (slow CI / fast-failing
   * source), the waiter never completes, the caller times out, and the re-read catches the job
   * pre-claim as {@code QUEUED} (round-6 retro: register-before-publish, not register-after).
   *
   * <p>Public (not on the interface) so the self-proxy's {@code @Transactional} advice applies —
   * Spring only weaves tx advice on public methods.
   */
  @Transactional
  public DiscoveryJobDto startJobWithId(UUID userId, StartDiscoveryJobRequest request, UUID jobId) {
    List<DiscoverySource> resolved = resolveSources(request.sourceKeys());
    if (resolved.isEmpty()) {
      throw new DiscoveryConstraintInvalidException(
          "zero enabled sources match the requested subset");
    }

    UUID traceId = request.traceId() != null ? request.traceId() : UUID.randomUUID();
    List<String> sourceKeys = new ArrayList<>(resolved.size());
    for (DiscoverySource s : resolved) {
      sourceKeys.add(s.getSourceKey());
    }

    DiscoveryJob job =
        DiscoveryJob.builder()
            .id(jobId)
            .userId(userId)
            .trigger(request.trigger())
            .requestedCount(request.requestedCount())
            .constraintsJson(objectMapper.valueToTree(request.constraints()))
            .sourcesRequested(sourceKeys)
            .status(DiscoveryJobStatus.QUEUED)
            .queuedAt(Instant.now())
            .candidatesSeen(0)
            .candidatesAfterFilter(0)
            .recipesIngested(0)
            .recipesSkippedDuplicate(0)
            .sourcesSucceeded(new ArrayList<>())
            .sourcesFailed(new ArrayList<>())
            .traceId(traceId)
            .build();
    // saveAndFlush: the response DTO carries optimisticVersion / queuedAt freshly bumped by
    // Hibernate, and `save()` doesn't flush — would surface stale state.
    DiscoveryJob saved = jobRepository.saveAndFlush(job);

    eventPublisher.publishEvent(
        new DiscoveryJobStartedEvent(
            saved.getId(),
            userId,
            saved.getTrigger(),
            saved.getRequestedCount(),
            List.copyOf(saved.getSourcesRequested()),
            saved.getTraceId(),
            Instant.now()));

    return jobMapper.toDto(saved);
  }

  @Override
  public DiscoveryJobDto runJobSync(
      UUID userId, StartDiscoveryJobRequest request, Duration timeout) {
    // Trigger validation — defence in depth per LLD line 471. The controller only forwards trusted
    // COLD_START requests, but the service guards against future re-routes.
    if (request.trigger() != DiscoveryJobTrigger.COLD_START) {
      throw new DiscoveryConstraintInvalidException("runJobSync only supports COLD_START trigger");
    }

    // Clamp the deadline to the configured hard cap (default 60s). Shorter requests honoured;
    // longer silently clamped for predictability (ticket invariant 3).
    Duration effective =
        timeout.compareTo(properties.syncTimeout()) > 0 ? properties.syncTimeout() : timeout;

    // Pre-generate the job id and register the sync waiter BEFORE startJob publishes
    // DiscoveryJobStartedEvent. Ordering is load-bearing: startJob is @Transactional and publishes
    // AFTER_COMMIT; the runner's listener is @Async + AFTER_COMMIT. If we registered the waiter
    // after startJob returned (post-commit), a fast/failing source on slow CI lets the runner
    // finalise the job and call completeSyncWaiter before the waiter exists — the waiter never
    // completes, runJobSync times out, and the re-read catches the job pre-claim as QUEUED
    // (observed: sync_fastColdStart got "QUEUED", sync_allSourcesDown got 200 not 502).
    // Registering first closes the race entirely. MUST still route startJobWithId through the
    // self-proxy — a direct this.startJobWithId() is a Spring self-invocation that bypasses the
    // @Transactional proxy, so it would run tx-less and the AFTER_COMMIT event would be dropped.
    UUID jobId = UUID.randomUUID();
    CompletableFuture<DiscoveryJobStatus> waiter = new CompletableFuture<>();
    runner.registerSyncWaiter(jobId, waiter);

    try {
      ((self != null) ? self : this).startJobWithId(userId, request, jobId);
      DiscoveryJobStatus terminal = waiter.get(effective.toMillis(), TimeUnit.MILLISECONDS);
      log.debug("runJobSync jobId={} completed terminal={}", jobId, terminal);
    } catch (TimeoutException te) {
      // Normal degraded response per LLD line 543 — NOT an error. Return the latest DTO; the job
      // continues in the background and the planner falls back.
      log.info("runJobSync deadline reached for jobId={}; returning latest DTO", jobId);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.info("runJobSync interrupted for jobId={}; returning latest DTO", jobId);
    } catch (ExecutionException ee) {
      log.error("runJobSync waiter failed for jobId={}", jobId, ee);
    } finally {
      // Always clear the waiter so a timeout/interrupt does not leak the map entry.
      runner.unregisterSyncWaiter(jobId);
    }

    // Re-read from DB so the response carries the most-recent counters the runner committed (the
    // waiter only carries the terminal status). A timeout returns the in-flight DTO (likely
    // RUNNING); the controller maps status → HTTP. Evict the request-thread persistence context
    // first so the read reflects the runner's cross-thread UPDATE rather than the stale QUEUED L1
    // entry (see the entityManager field comment — OSIV-on under the shadowed test properties).
    if (entityManager != null) {
      entityManager.clear();
    }
    return getJob(jobId).orElseThrow(() -> new DiscoveryJobNotFoundException(jobId));
  }

  @Override
  @Transactional
  public void cancelJob(UUID userId, UUID jobId) {
    DiscoveryJob job =
        jobRepository
            .findByIdAndUserId(jobId, userId)
            .orElseThrow(() -> new DiscoveryJobNotFoundException(jobId));
    if (TERMINAL_STATES.contains(job.getStatus())) {
      throw new DiscoveryJobAlreadyTerminalException(jobId, job.getStatus());
    }
    if (job.getStatus() == DiscoveryJobStatus.RUNNING) {
      // 01d: set the in-memory cancellation flag; the runner sees it on its next per-candidate
      // iteration and finalises early. The endpoint returns 200 with status=RUNNING; the caller
      // polls. Per ticket invariants 32-35.
      runner.requestCancellation(jobId);
      return;
    }
    // QUEUED branch: flip via native UPDATE (round-8 retro). The async DiscoveryJobRunner may
    // pick this job up between our read above and the write; a load+setStatus+saveAndFlush races
    // its persistence context and throws StaleObjectStateException (@Version on DiscoveryJob).
    // The native UPDATE bumps optimisticVersion in one statement, side-stepping the dirty-check.
    runner.requestCancellation(jobId);
    int rows =
        jobRepository.markCancelled(
            jobId, DiscoveryJobStatus.FAILED, Instant.now(), "cancelled by user");
    if (rows == 0) {
      throw new DiscoveryJobNotFoundException(jobId);
    }
  }

  @Override
  @Transactional
  public DiscoverySourceDto enableSource(String sourceKey) {
    DiscoverySource source =
        sourceRepository
            .findBySourceKey(sourceKey)
            .orElseThrow(() -> new DiscoverySourceNotFoundException(sourceKey));
    source.setEnabled(true);
    source.setUserDisabled(false);
    DiscoverySource saved = sourceRepository.saveAndFlush(source);
    return sourceMapper.toDto(saved);
  }

  @Override
  @Transactional
  public DiscoverySourceDto disableSource(String sourceKey) {
    DiscoverySource source =
        sourceRepository
            .findBySourceKey(sourceKey)
            .orElseThrow(() -> new DiscoverySourceNotFoundException(sourceKey));
    source.setEnabled(false);
    // Deliberately not touching userDisabled — admin-driven disable is distinct from user-driven
    // (LLD line 80). 01d's runner / user-Settings path own the user flag.
    DiscoverySource saved = sourceRepository.saveAndFlush(source);
    return sourceMapper.toDto(saved);
  }

  @Override
  public OrphanSweepResultDto runOrphanSweep() {
    return runner.sweepOrphansNow();
  }

  // ===== DiscoveryQueryService — read path =====

  @Override
  @Transactional(readOnly = true)
  public Optional<DiscoveryJobDto> getJob(UUID jobId) {
    return jobRepository.findById(jobId).map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DiscoveryJobDto> getJobForUser(UUID userId, UUID jobId) {
    return jobRepository.findByIdAndUserId(jobId, userId).map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DiscoveryJobDto> listJobsForUser(UUID userId, Pageable pageable) {
    return jobRepository.findByUserIdOrderByQueuedAtDesc(userId, pageable).map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DiscoveryScrapeLogEntryDto> getScrapeLog(UUID jobId, Pageable pageable) {
    // 404 pre-check — LLD line 435 leaves the contract loose; explicit 404 surfaces the
    // user-facing semantics (vs. silently returning an empty page for an unknown job).
    if (!jobRepository.existsById(jobId)) {
      throw new DiscoveryJobNotFoundException(jobId);
    }
    return scrapeLogRepository
        .findByJobIdOrderByOccurredAt(jobId, pageable)
        .map(scrapeLogMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DiscoverySourceDto> listSources() {
    List<DiscoverySource> all = sourceRepository.findAll();
    List<DiscoverySourceDto> dtos = new ArrayList<>(all.size());
    for (DiscoverySource s : all) {
      dtos.add(sourceMapper.toDto(s));
    }
    // Stable UI ordering — ticket invariant 19.
    dtos.sort(Comparator.comparing(DiscoverySourceDto::displayName, Comparator.naturalOrder()));
    return dtos;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DiscoverySourceDto> getSource(String sourceKey) {
    return sourceRepository.findBySourceKey(sourceKey).map(sourceMapper::toDto);
  }

  // ===== helpers =====

  private List<DiscoverySource> resolveSources(List<String> requestedKeys) {
    if (requestedKeys == null) {
      return sourceRepository.findByEnabledTrue();
    }
    if (requestedKeys.isEmpty()) {
      // Caller explicitly sent an empty array — treat as "match nothing" to fail fast at the
      // zero-source guard in startJob.
      return Collections.emptyList();
    }
    List<DiscoverySource> found = sourceRepository.findBySourceKeyIn(requestedKeys);
    Map<String, DiscoverySource> byKey = new HashMap<>();
    for (DiscoverySource s : found) {
      byKey.put(s.getSourceKey(), s);
    }
    List<String> errors = new ArrayList<>();
    List<DiscoverySource> resolved = new ArrayList<>();
    for (String requested : requestedKeys) {
      DiscoverySource match = byKey.get(requested);
      if (match == null || !match.isEnabled()) {
        errors.add(requested);
      } else {
        resolved.add(match);
      }
    }
    if (!errors.isEmpty()) {
      throw new DiscoveryConstraintInvalidException(
          "unknown or disabled source keys: " + String.join(", ", errors), errors);
    }
    return resolved;
  }
}
