package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Read shape of an inventory item. {@code version} is the JPA {@code @Version} value. */
public record InventoryItemDto(
    UUID id,
    UUID userId,
    String name,
    String category,
    StorageLocation storageLocation,
    TrackingMode trackingMode,
    BigDecimal quantity,
    String unit,
    BigDecimal costPaid,
    StapleStatus status,
    boolean isStaple,
    LocalDate expiryDate,
    String ingredientMappingKey,
    String notes,
    ItemSource source,
    String sourceRef,
    ItemLifecycleStatus itemStatus,
    FreezerExtensionDto freezerExtension,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
