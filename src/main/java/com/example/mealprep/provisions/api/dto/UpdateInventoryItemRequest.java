package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.validation.StorageLocationValidatable;
import com.example.mealprep.provisions.validation.ValidQuantity;
import com.example.mealprep.provisions.validation.ValidStorageLocation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of {@code PUT /api/v1/provisions/inventory/{itemId}}. Full replacement; the {@code
 * expectedVersion} carries the JPA {@code @Version} value the caller last saw — a mismatch surfaces
 * as 409.
 *
 * <p>Lifecycle transitions ({@link ItemLifecycleStatus#EXHAUSTED}/{@link
 * ItemLifecycleStatus#SPOILED}/ {@link ItemLifecycleStatus#WASTED}) also have dedicated {@code
 * mark-spoiled} / {@code mark-exhausted} endpoints; {@code itemStatus} is accepted on this full-
 * replacement body so a status flip can ride a PUT as well.
 */
@ValidStorageLocation
public record UpdateInventoryItemRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 64) String category,
    @NotNull StorageLocation storageLocation,
    @NotNull TrackingMode trackingMode,
    @ValidQuantity BigDecimal quantity,
    @Size(max = 16) String unit,
    BigDecimal costPaid,
    StapleStatus status,
    boolean isStaple,
    LocalDate expiryDate,
    @Size(max = 128) String ingredientMappingKey,
    @Size(max = 255) String notes,
    @NotNull ItemSource source,
    @Size(max = 128) String sourceRef,
    @NotNull ItemLifecycleStatus itemStatus,
    @Valid FreezerExtensionDto freezerExtension,
    long expectedVersion)
    implements StorageLocationValidatable {}
