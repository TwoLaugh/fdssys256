package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/households/current/members} — direct add (PRIMARY only). The
 * actor is server-resolved via {@code CurrentUserResolver}; {@code userId} here is the target being
 * added.
 *
 * @param userId target user being seated as a household member.
 * @param role intended role on join.
 * @param priority nullable; defaults to 100 when null.
 * @param displayName nullable per-household label for the member.
 */
public record AddMemberRequest(
    @NotNull UUID userId,
    @NotNull HouseholdRole role,
    @Min(1) @Max(1000) Integer priority,
    @Size(max = 64) String displayName) {}
