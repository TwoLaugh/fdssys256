package com.example.mealprep.planner.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/plans/{planId}/abandon} (planner-01j). {@code reason} is
 * optional free-text persisted to {@code Plan.abandonedReason}.
 */
public record AbandonPlanRequest(@Size(max = 255) String reason) {}
