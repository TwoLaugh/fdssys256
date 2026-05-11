package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body for {@code POST /api/v1/households/current/members/{memberId}/role}. PRIMARY-only;
 * demoting the last primary while other members remain yields 409 {@code
 * LastPrimaryRemovalException}.
 *
 * @param newRole the desired role; equal to the current role is a no-op (200, no event).
 * @param expectedVersion required for optimistic locking; mismatched values yield 409.
 */
public record ChangeRoleRequest(
    @NotNull HouseholdRole newRole, @PositiveOrZero long expectedVersion) {}
