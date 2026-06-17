package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanGenerationJobDto;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs {@link PlanComposer#compose} <b>asynchronously</b> so the generate request returns
 * immediately instead of blocking the caller for the whole Stage-A&rarr;D run (which can take tens
 * of seconds, the optimiser being compute-bound). The HTTP thread gets a {@link
 * PlanGenerationJobDto} in {@code RUNNING} the instant the job is scheduled; the frontend shows a
 * processing state and polls {@link #get(UUID)} until the job is {@code COMPLETED} (then loads the
 * {@code planId}) or {@code FAILED}.
 *
 * <p>Composition is dispatched to the bounded default {@code applicationTaskExecutor} (see {@code
 * DefaultAsyncConfig} — core 4 / max 8 / bounded queue / CallerRunsPolicy back-pressure) via a plain
 * {@link Executor#execute(Runnable)} rather than an {@code @Async} method, which keeps this a single
 * self-contained bean (no second runner bean + no {@code @Lazy} cycle to break) and reuses the same
 * back-pressured pool the rest of the app's background work uses. The worker thread runs {@code
 * compose} under its own transaction (the composer is {@code @Transactional}); {@code userId} is
 * passed explicitly so no inherited security/transaction context is required.
 *
 * <p>Idempotency-Key replays short-circuit: if the composer already has a cached plan for the key,
 * the returned job is {@code COMPLETED} immediately (no work scheduled), mirroring the synchronous
 * endpoint's {@code 200}-replay semantics.
 *
 * <p>Jobs live in an in-memory map — a generation completes well within the process lifetime, so
 * durability is not required for v1; they do NOT survive a restart (the durable-job / SSE-progress
 * upgrade is tracked in the backlog). The single-flight DB lease the composer already takes still
 * guards against concurrent generations for the same household/week; a job that loses that race
 * fails with a short error token rather than blocking.
 */
@Service
public class PlanGenerationJobService {

  private static final Logger log = LoggerFactory.getLogger(PlanGenerationJobService.class);

  private final PlanComposer planComposer;
  private final Executor executor;
  private final Map<UUID, PlanGenerationJobDto> jobs = new ConcurrentHashMap<>();

  public PlanGenerationJobService(PlanComposer planComposer, Executor executor) {
    this.planComposer = planComposer;
    this.executor = executor;
  }

  /**
   * Schedule (or replay) a generation. Returns immediately: a {@code COMPLETED} job for an
   * idempotency replay, otherwise a {@code RUNNING} job whose composition proceeds on a background
   * worker. {@code idempotencyKey} may be null.
   */
  public PlanGenerationJobDto submit(
      GeneratePlanRequest request, UUID userId, String idempotencyKey) {
    UUID jobId = UUID.randomUUID();

    Optional<UUID> cached = planComposer.cachedPlanIdFor(userId, idempotencyKey);
    if (cached.isPresent()) {
      PlanGenerationJobDto replay =
          PlanGenerationJobDto.running(jobId, request.householdId(), request.weekStartDate())
              .completed(cached.get(), true);
      jobs.put(jobId, replay);
      return replay;
    }

    PlanGenerationJobDto running =
        PlanGenerationJobDto.running(jobId, request.householdId(), request.weekStartDate());
    jobs.put(jobId, running);
    executor.execute(() -> runGeneration(jobId, request, userId, idempotencyKey));
    return running;
  }

  /** Current state of a job, or empty if the id is unknown (or evicted by a restart). */
  public Optional<PlanGenerationJobDto> get(UUID jobId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  private void runGeneration(
      UUID jobId, GeneratePlanRequest request, UUID userId, String idempotencyKey) {
    try {
      UUID planId = planComposer.compose(request, userId, idempotencyKey);
      jobs.computeIfPresent(jobId, (k, job) -> job.completed(planId, false));
    } catch (RuntimeException e) {
      log.warn(
          "Async plan generation job {} failed (household {}, week {}): {}",
          jobId,
          request.householdId(),
          request.weekStartDate(),
          e.toString());
      jobs.computeIfPresent(jobId, (k, job) -> job.failed(errorCode(e)));
    }
  }

  /** A short, stable machine token for the failure, derived from the exception type. */
  private static String errorCode(RuntimeException e) {
    String simple = e.getClass().getSimpleName();
    if (simple.toLowerCase(java.util.Locale.ROOT).contains("lock")
        || simple.toLowerCase(java.util.Locale.ROOT).contains("lease")
        || simple.toLowerCase(java.util.Locale.ROOT).contains("conflict")) {
      return "lease-conflict";
    }
    return simple.isBlank() ? "error" : simple;
  }
}
