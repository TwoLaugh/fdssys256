package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Tier-4 on-demand price refresh. Per lld/grocery.md line 478.
 *
 * <p>The acting user is resolved server-side by {@code CurrentUserResolver} (the project-wide
 * convention — grocery-7), so the request body carries ONLY the keys to refresh and the {@code
 * useProviderQuote} flag; it never accepts a client-supplied {@code userId}.
 */
public record RefreshPricesRequest(
    @Size(max = 200) List<@NotBlank String> ingredientMappingKeys, boolean useProviderQuote) {}
