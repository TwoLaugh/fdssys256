package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/nutrition/health-directives/{directiveId}/reject}. Records the
 * caller's reason; no safety gate runs on reject.
 */
public record RejectDirectiveRequest(
    @Size(max = 255) String rejectionReason, @Min(0) long expectedVersion) {}
