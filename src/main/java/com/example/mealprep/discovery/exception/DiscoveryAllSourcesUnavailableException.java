package com.example.mealprep.discovery.exception;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Thrown when a synchronous discovery job finalises {@code FAILED} with every requested source
 * unavailable (no source produced any successful ingest). Surfaces ONLY on the sync admin endpoint
 * ({@code POST /admin/jobs/sync}) per LLD line 578 — the async path records the failure in {@code
 * sourcesFailed} and the planner inspects the DTO instead.
 *
 * <p>Mapped to HTTP 502 by {@code DiscoveryExceptionHandler} per LLD line 452.
 */
public class DiscoveryAllSourcesUnavailableException extends DiscoveryException {

  private final UUID jobId;
  private final List<String> failedSources;

  public DiscoveryAllSourcesUnavailableException(UUID jobId, List<String> failedSources) {
    super("all sources unavailable for job " + jobId + ": " + failedSources);
    this.jobId = jobId;
    this.failedSources =
        failedSources == null ? Collections.emptyList() : List.copyOf(failedSources);
  }

  public UUID jobId() {
    return jobId;
  }

  public List<String> failedSources() {
    return failedSources;
  }
}
