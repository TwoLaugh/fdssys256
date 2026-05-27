package com.example.mealprep.grocery.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape for Tier-3 provider connection state. Per lld/grocery.md lines 441-445. Deliberately
 * omits {@code sessionState} — cookies never cross the API boundary.
 */
public record GroceryProviderStateDto(
    UUID id,
    UUID userId,
    String providerKey,
    boolean enabled,
    Instant sessionExpiresAt,
    Instant lastLoginAt,
    Instant lastFailureAt,
    String lastFailureReason,
    int consecutiveFailures,
    boolean scheduledRefreshEnabled,
    int refreshTopNIngredients) {}
