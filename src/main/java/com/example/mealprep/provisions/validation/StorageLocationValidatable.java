package com.example.mealprep.provisions.validation;

import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;

/**
 * Internal contract implemented by {@code CreateInventoryItemRequest} and {@code
 * UpdateInventoryItemRequest} so {@link ValidStorageLocationValidator} can validate either shape
 * without reflection. Not part of the public API of the module.
 */
public interface StorageLocationValidatable {

  StorageLocation storageLocation();

  TrackingMode trackingMode();

  FreezerExtensionDto freezerExtension();
}
