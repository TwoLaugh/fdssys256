package com.example.mealprep.household.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/households/current/merge}. Null or empty {@code
 * eaterUserIds} means "all current household members" (LLD line 318). Each non-null list element
 * must be a non-null UUID; null entries are rejected as 400.
 */
public record MergeSoftPreferencesRequest(List<@NotNull UUID> eaterUserIds) {}
