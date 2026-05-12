package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One substitution event on a grocery order import. LLD line 464 verbatim. {@code orderedProductId}
 * is the SKU the user originally ordered; {@code substitutedProductId} is what the supplier
 * delivered. The {@link com.example.mealprep.provisions.domain.entity.SupplierProduct} cached for
 * {@code orderedProductId} gets a new {@code SubstitutionRecord} appended.
 */
public record GroceryOrderSubstitution(
    @NotBlank @Size(max = 128) String orderedProductId,
    @NotBlank @Size(max = 128) String substitutedProductId,
    @Nullable @Size(max = 255) String reason) {}
