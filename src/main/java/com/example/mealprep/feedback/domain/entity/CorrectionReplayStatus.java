package com.example.mealprep.feedback.domain.entity;

/**
 * Outcome of replaying a misclassification correction against the corrected destination. Values per
 * lld/feedback.md §Entities line 223, extended with {@code PENDING_REPLAY} per ticket 01a
 * divergence note (the LLD's Flow 4 step 5 references this initial value but the enum listing omits
 * it).
 *
 * <p>The column is {@code NOT NULL}; the initial insert sets {@code PENDING_REPLAY}, the replayer
 * in feedback-01f flips it to a terminal value.
 */
public enum CorrectionReplayStatus {
  PENDING_REPLAY,
  APPLIED,
  FAILED,
  DESTINATION_REJECTED
}
