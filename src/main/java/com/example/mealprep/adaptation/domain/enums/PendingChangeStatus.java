package com.example.mealprep.adaptation.domain.enums;

/**
 * Lifecycle of an {@code adaptation_pending_changes} row. Verbatim from {@code
 * lld/adaptation-pipeline.md} line 135.
 */
public enum PendingChangeStatus {
  PENDING,
  ACCEPTED,
  REJECTED,
  MODIFIED,
  SUPERSEDED,
  EXPIRED
}
