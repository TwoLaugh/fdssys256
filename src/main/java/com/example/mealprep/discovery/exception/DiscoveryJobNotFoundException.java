package com.example.mealprep.discovery.exception;

import java.util.UUID;

/**
 * Thrown when a {@code DiscoveryJob} lookup ({@code GET /jobs/{jobId}}, cancel, scrape-log) finds
 * no row for the supplied id, or when {@code findByIdAndUserId} returns empty because the requested
 * job belongs to another user. Mapped to HTTP 404 by {@code DiscoveryExceptionHandler}.
 */
public class DiscoveryJobNotFoundException extends DiscoveryException {

  private final UUID jobId;

  public DiscoveryJobNotFoundException(UUID jobId) {
    super("Discovery job not found: " + jobId);
    this.jobId = jobId;
  }

  public UUID jobId() {
    return jobId;
  }
}
