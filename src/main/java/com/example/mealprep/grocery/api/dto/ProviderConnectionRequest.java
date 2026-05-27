package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Tier-3 provider connection upsert. Per lld/grocery.md line 477. */
public record ProviderConnectionRequest(
    @NotBlank String providerKey,
    boolean enabled,
    boolean scheduledRefreshEnabled,
    @Min(0) @Max(200) Integer refreshTopNIngredients) {}
