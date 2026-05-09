package com.example.mealprep.household.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read shape of a household. {@code version} is the JPA {@code @Version} value. */
public record HouseholdDto(
    UUID id,
    String name,
    UUID createdByUserId,
    List<HouseholdMemberDto> members,
    Instant createdAt,
    long version) {}
