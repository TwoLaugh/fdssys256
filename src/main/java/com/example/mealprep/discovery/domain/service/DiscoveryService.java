package com.example.mealprep.discovery.domain.service;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import java.time.Duration;
import java.util.UUID;

/**
 * Public update-side facade for the discovery module. Injected by {@code planner} (cold-start, via
 * {@code runJobSync}) and {@code recipe} (user-initiated / scheduled, via {@code startJob}).
 *
 * <p>Per LLD lines 346-356. {@code runJobSync} throws {@code UnsupportedOperationException} until
 * discovery-01f wires the CompletableFuture coordination.
 */
public interface DiscoveryService {

  /**
   * Enqueue an async discovery job. Validates the constraints document, resolves the source set
   * (null {@code sourceKeys} → all currently-enabled sources; otherwise the named subset), persists
   * the job with {@code status = QUEUED}, and publishes {@code DiscoveryJobStartedEvent}
   * AFTER_COMMIT for the (01d) runner to pick up. Caller polls via {@code
   * DiscoveryQueryService.getJob}.
   */
  DiscoveryJobDto startJob(UUID userId, StartDiscoveryJobRequest request);

  /**
   * Cold-start path: enqueue per {@link #startJob} then block on the runner's completion future
   * until terminal or {@code timeout}. Deferred to discovery-01f — 01b throws {@code
   * UnsupportedOperationException}.
   */
  DiscoveryJobDto runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout);

  /**
   * Idempotent cancellation. {@code QUEUED} flips to {@code FAILED} with {@code errorSummary =
   * "cancelled by user"}; terminal states throw {@code DiscoveryJobAlreadyTerminalException}. The
   * {@code RUNNING} branch is a temporary 422 in 01b — discovery-01d wires the in-memory
   * cancellation flag and changes this branch to set the flag and return success.
   */
  void cancelJob(UUID userId, UUID jobId);

  /**
   * Admin: flip {@code enabled = true} on a source. {@code userDisabled} is explicitly cleared so a
   * subsequent user re-enable round trips. 404 if the key is unknown.
   */
  DiscoverySourceDto enableSource(String sourceKey);

  /**
   * Admin: flip {@code enabled = false}. Does NOT set {@code userDisabled = true} — that flag is
   * reserved for the user-driven path (Settings toggle), per LLD line 80. 404 if the key is
   * unknown.
   */
  DiscoverySourceDto disableSource(String sourceKey);

  /**
   * Sweep orphan {@code RUNNING} jobs whose heartbeat went stale. Implementation lands with
   * discovery-01d's runner; 01b returns a placeholder {@code {resumedCount: 0}}.
   */
  OrphanSweepResultDto runOrphanSweep();
}
