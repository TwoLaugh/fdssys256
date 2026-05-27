package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Tier-3 place-order request. Per lld/grocery.md line 475 (the LLD names it but leaves the shape
 * implicit; 01a mirrors {@link QuoteRequest} — the order id is the only required input).
 */
public record PlaceOrderRequest(@NotNull UUID groceryOrderId) {}
