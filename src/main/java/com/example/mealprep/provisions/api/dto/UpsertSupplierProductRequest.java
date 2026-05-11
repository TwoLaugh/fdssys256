package com.example.mealprep.provisions.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/provisions/supplier-products}. Upsert keyed by {@code
 * (supplier, productId)} — every call refreshes {@code lastChecked} (the freshness clock always
 * ticks). {@code substitutionHistory} is preserved on update (this endpoint never touches it).
 */
public record UpsertSupplierProductRequest(
    @NotBlank @Size(max = 128) String productId,
    @NotBlank @Size(max = 32) String supplier,
    @NotBlank @Size(max = 255) String name,
    @DecimalMin("0.0") @Digits(integer = 6, fraction = 2) BigDecimal price,
    @DecimalMin("0.0") @Digits(integer = 4, fraction = 4) BigDecimal pricePerUnit,
    @Size(max = 16) String unit,
    Integer packSizeG,
    @Size(max = 16) String packSizeUnit,
    @Size(max = 64) String category,
    @DecimalMin("0.0") @Digits(integer = 6, fraction = 2) BigDecimal clubcardPrice,
    @NotNull @PastOrPresent LocalDate lastChecked,
    @Size(max = 128) String ingredientMappingKey) {}
