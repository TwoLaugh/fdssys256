package com.example.mealprep.discovery.exception;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import java.util.UUID;

/**
 * Thrown by {@code cancelJob} when the addressed job is in a terminal status ({@code SUCCEEDED},
 * {@code FAILED}, {@code PARTIAL}) or — temporarily in 01b — in {@code RUNNING}. The 01b ticket
 * defers in-flight cancellation to 01d's runner; this exception covers both branches with distinct
 * detail messages. Mapped to HTTP 422 by {@code DiscoveryExceptionHandler}.
 */
public class DiscoveryJobAlreadyTerminalException extends DiscoveryException {

  private final UUID jobId;
  private final DiscoveryJobStatus status;

  public DiscoveryJobAlreadyTerminalException(UUID jobId, DiscoveryJobStatus status) {
    super(detail(jobId, status));
    this.jobId = jobId;
    this.status = status;
  }

  private static String detail(UUID jobId, DiscoveryJobStatus status) {
    if (status == DiscoveryJobStatus.RUNNING) {
      // 01b limitation: in-memory cancellation flag for RUNNING jobs ships with 01d.
      return "cancellation of in-flight jobs not supported in this build; queued-only (jobId="
          + jobId
          + ")";
    }
    return "job already in terminal state: " + status + " (jobId=" + jobId + ")";
  }

  public UUID jobId() {
    return jobId;
  }

  public DiscoveryJobStatus status() {
    return status;
  }
}
