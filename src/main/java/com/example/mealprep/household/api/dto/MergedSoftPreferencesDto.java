package com.example.mealprep.household.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only merged soft-preferences document produced by {@code HouseholdMergeService}. Not
 * persisted (LLD line 326); the planner consumes a transient instance per slot.
 *
 * <p>LLD divergence note: {@code householdId} is nullable — the {@code
 * mergeSoftPreferencesForUsers} variant bypasses household lookup and returns {@code null}. LLD
 * line 198 declares {@code UUID} without explicit nullability; 01e widens to nullable.
 *
 * <p>Per LLD line 197 + style-guide §JSONB, no {@code schemaVersion} field is shipped — the DTO is
 * never persisted so the persistence-conditional rule doesn't apply.
 */
public record MergedSoftPreferencesDto(
    UUID householdId,
    List<UUID> contributingUserIds,
    TasteProfileDocument mergedTasteProfile,
    LifestyleConfigDocument mergedLifestyleConfig,
    List<UUID> userIdsByPriority,
    MergeStrategy strategy,
    Instant mergedAt) {}
