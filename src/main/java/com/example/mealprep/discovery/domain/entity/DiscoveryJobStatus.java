package com.example.mealprep.discovery.domain.entity;

/**
 * Lifecycle of a {@code DiscoveryJob}. Terminal: {@code SUCCEEDED}, {@code FAILED}, {@code
 * PARTIAL}. Per LLD line 197.
 */
public enum DiscoveryJobStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  PARTIAL
}
