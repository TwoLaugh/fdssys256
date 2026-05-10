package com.example.mealprep.household.api.dto;

import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a household's settings aggregate. {@code version} is the JPA {@code @Version}
 * value, returned to callers so a subsequent {@code PUT} can supply {@code expectedVersion} for
 * optimistic concurrency.
 */
public record HouseholdSettingsDto(
    UUID id,
    UUID householdId,
    HouseholdSettingsDocument document,
    long version,
    Instant createdAt) {}
