package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/households/current/invites}. The server caps {@code
 * expiresAt} at {@code now + 30 days} silently — values past the cap are truncated, not rejected.
 * {@code @Future} rejects past timestamps at the controller layer (400).
 *
 * @param issuedForUserId optional pre-targeted recipient; if set, only that user can accept.
 * @param intendedRole the role the accepter will be granted on accept ({@code primary} or {@code
 *     member}).
 * @param expiresAt must be strictly in the future; capped server-side at {@code now + 30 days}.
 */
public record CreateInviteRequest(
    UUID issuedForUserId,
    @NotNull HouseholdRole intendedRole,
    @NotNull @Future Instant expiresAt) {}
