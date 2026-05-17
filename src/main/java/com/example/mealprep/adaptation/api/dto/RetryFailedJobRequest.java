package com.example.mealprep.adaptation.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Admin body for {@code POST /admin/retry-failed-job}. Re-enqueues a fresh copy of a FAILED job
 * with {@code parent_decision_id} chained to the original for audit.
 *
 * <p>Per ticket 01f §AdaptationAdminController.
 */
public record RetryFailedJobRequest(@NotNull UUID jobId) {}
