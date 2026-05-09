package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/** Read shape of a single member of a household. */
public record HouseholdMemberDto(
    UUID id,
    UUID householdId,
    UUID userId,
    HouseholdRole role,
    String displayName,
    int priority,
    Instant joinedAt,
    long version) {}
