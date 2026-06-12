package com.example.mealprep.discovery.domain.entity;

/**
 * Lifecycle of a {@code DiscoveryJob}. Terminal: {@code SUCCEEDED}, {@code FAILED}, {@code
 * PARTIAL}, {@code CANCELLED}. Per LLD line 197 + ticket discovery-cancelled-status: {@code
 * CANCELLED} is the user-cancel terminal (QUEUED-cancel flips immediately; RUNNING-cancel finalises
 * when the runner stops between candidates). {@code FAILED} is reserved for genuine failures —
 * "FAILED means failed".
 */
public enum DiscoveryJobStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  PARTIAL,
  CANCELLED
}
