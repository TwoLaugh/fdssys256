package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.validation.ValidQuantity;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/v1/provisions/inventory/{itemId}/quantity} (LLD endpoint table line
 * 500). A focused quantity edit for a QUANTITY-tracked item — the user corrects "actually I have
 * 300g, not 500g" without re-sending the whole item via PUT.
 *
 * <p>{@code newQuantity} is the absolute replacement value (non-negative; null is rejected — this
 * endpoint only edits quantity-tracked rows). {@code expectedVersion} carries the JPA
 * {@code @Version} the caller last saw; a mismatch surfaces as 409. The adjustment publishes {@code
 * ItemQuantityAdjustedEvent(source = MANUAL)} and writes one {@code actor = USER} audit row; a
 * no-op (same quantity) writes nothing and does not bump {@code version}.
 */
public record AdjustInventoryQuantityRequest(
    @NotNull @ValidQuantity BigDecimal newQuantity, long expectedVersion) {}
