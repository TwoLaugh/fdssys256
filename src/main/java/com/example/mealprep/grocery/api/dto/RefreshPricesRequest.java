package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Tier-4 on-demand price refresh. Per lld/grocery.md line 478. */
public record RefreshPricesRequest(
    @NotNull UUID userId,
    @Size(max = 200) List<@NotBlank String> ingredientMappingKeys,
    boolean useProviderQuote) {}
