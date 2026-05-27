package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Tier-3 cancel-order request. Per lld/grocery.md line 475. */
public record CancelOrderRequest(@NotNull UUID groceryOrderId, @Size(max = 64) String reason) {}
