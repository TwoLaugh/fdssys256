package com.example.mealprep.provisions.api.dto;

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
 * Body of {@code POST /api/v1/provisions/inventory}. The server resolves {@code userId} from the
 * session — never accepted from the request — so user A cannot create items for user B.
 */
@ValidStorageLocation
public record CreateInventoryItemRequest(
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
    @Valid FreezerExtensionDto freezerExtension)
    implements StorageLocationValidatable {}
