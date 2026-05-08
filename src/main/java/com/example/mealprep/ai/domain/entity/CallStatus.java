package com.example.mealprep.ai.domain.entity;

/**
 * Lifecycle state for an {@link AiCallLog} row. The dispatcher INSERTs {@link #PENDING} before
 * making the network call and exactly once UPDATEs to {@link #SUCCEEDED} or {@link #FAILED}.
 */
public enum CallStatus {
  PENDING,
  SUCCEEDED,
  FAILED
}
