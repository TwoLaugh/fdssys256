package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a household invite. {@code inviteCode} is bearer-only secrecy: it is non-null only
 * on the response of the {@code POST /current/invites} create endpoint; on every list / accept
 * response it is redacted to {@code null}.
 */
public record HouseholdInviteDto(
    UUID id,
    UUID householdId,
    String inviteCode,
    UUID issuedByUserId,
    UUID issuedForUserId,
    HouseholdRole intendedRole,
    Instant expiresAt,
    Instant acceptedAt,
    Instant revokedAt,
    InviteStatus status) {}
