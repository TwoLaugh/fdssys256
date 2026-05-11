package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.validation.ValidWasteQuantity;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write request for the waste-log endpoint. The shape is locked by ticket 01e (LLD line 511
 * references {@code LogWasteRequest} but doesn't pin the fields).
 *
 * <p>{@code @ValidWasteQuantity} enforces the cross-field shape: {@code quantity != null ⇒ unit !=
 * null}. The cross-resource "waste ≤ remaining inventory" check is service-side (LLD line 550).
 */
@ValidWasteQuantity
public record LogWasteRequest(
    UUID inventoryItemId,
    @NotBlank @Size(max = 128) String itemName,
    @DecimalMin(value = "0.0", inclusive = true) BigDecimal quantity,
    @Size(max = 16) String unit,
    @NotNull WasteReason reason,
    @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 6, fraction = 2)
        BigDecimal costEstimate,
    @NotNull @PastOrPresent LocalDate occurredOn,
    @Size(max = 255) String notes) {}
