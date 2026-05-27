package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Tier-3 create-order-draft request. Per lld/grocery.md line 475. */
public record CreateOrderRequest(@NotNull UUID shoppingListId, @NotBlank String providerKey) {}
