package com.example.mealprep.household.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/households/current/members/{memberId}}. PATCH semantics —
 * absent (null) {@code priority} / {@code displayName} means "no change", not "clear to null". The
 * dedicated {@code /role} endpoint owns role mutation.
 *
 * @param priority nullable; null = no change.
 * @param displayName nullable; null = no change.
 * @param expectedVersion required for optimistic locking; mismatched values yield 409.
 */
public record UpdateMemberRequest(
    @Min(1) @Max(1000) Integer priority,
    @Size(max = 64) String displayName,
    @PositiveOrZero long expectedVersion) {}
