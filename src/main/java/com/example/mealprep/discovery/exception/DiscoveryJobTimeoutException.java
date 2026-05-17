package com.example.mealprep.discovery.exception;

import java.time.Duration;
import java.util.UUID;

/**
 * Thrown when a synchronous discovery job did not reach a terminal state within the caller's
 * deadline AND the caller requested strict timeout semantics ({@code ?strictTimeout=true}). The
 * default (non-strict) behaviour returns 200 + the partial DTO per LLD line 543 — the timeout is a
 * normal degraded response, not an error.
 *
 * <p>Mapped to HTTP 408 by {@code DiscoveryExceptionHandler} per LLD line 453.
 */
public class DiscoveryJobTimeoutException extends DiscoveryException {

  private final UUID jobId;
  private final Duration timeout;

  public DiscoveryJobTimeoutException(UUID jobId, Duration timeout) {
    super("discovery job " + jobId + " did not reach a terminal state within " + timeout);
    this.jobId = jobId;
    this.timeout = timeout;
  }

  public UUID jobId() {
    return jobId;
  }

  public Duration timeout() {
    return timeout;
  }
}
