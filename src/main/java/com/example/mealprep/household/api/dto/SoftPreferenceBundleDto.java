package com.example.mealprep.household.api.dto;

import java.util.UUID;

/**
 * One user's soft-preferences as exposed via the {@code SoftPreferencesReader} SPI. Both {@code
 * tasteProfile} and {@code lifestyleConfig} are nullable — a user may not have either yet.
 * Lightweight household-owned stub for 01e; preference-01c owns the canonical record (the migration
 * is a mechanical rename + import update).
 */
public record SoftPreferenceBundleDto(
    UUID userId, TasteProfileDocument tasteProfile, LifestyleConfigDocument lifestyleConfig) {}
