package com.example.mealprep.planner.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/plans/{planId}/reject} (planner-01j). {@code reason} is
 * optional free-text persisted to {@code Plan.rejectedReason} for the optimisation-loop feedback
 * path.
 */
public record RejectPlanRequest(@Size(max = 255) String reason) {}
