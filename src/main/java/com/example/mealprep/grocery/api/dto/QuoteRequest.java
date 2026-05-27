package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Tier-3 quote request. Per lld/grocery.md line 475. */
public record QuoteRequest(@NotNull UUID groceryOrderId) {}
