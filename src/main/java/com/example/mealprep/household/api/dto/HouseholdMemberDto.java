package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a single member of a household.
 *
 * <p>{@code username} is the member's login username, read-only and joined from the auth module at
 * mapping time (one batched lookup per members list — see {@code HouseholdServiceImpl}). Nullable:
 * a soft-deleted user no longer resolves, in which case the UI falls back to {@code displayName} /
 * userId short-form.
 */
public record HouseholdMemberDto(
    UUID id,
    UUID householdId,
    UUID userId,
    HouseholdRole role,
    String displayName,
    String username,
    int priority,
    Instant joinedAt,
    long version) {}
