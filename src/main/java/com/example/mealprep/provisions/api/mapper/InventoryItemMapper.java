package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import org.mapstruct.Mapper;

/**
 * Inventory entity ↔ DTO mapping. The freezer-extension fields collapse into a single nested {@link
 * FreezerExtensionDto} when {@code storageLocation = FREEZER}; for the other locations the DTO
 * carries {@code null}, mirroring the wire schema.
 */
@Mapper(componentModel = "spring")
public interface InventoryItemMapper {

  default InventoryItemDto toDto(InventoryItem entity) {
    if (entity == null) {
      return null;
    }
    FreezerExtensionDto freezerExtension = null;
    if (entity.getStorageLocation() == StorageLocation.FREEZER) {
      freezerExtension =
          new FreezerExtensionDto(
              entity.getFrozenAt(),
              entity.getMaxFreezeWeeks(),
              entity.getDefrostMethod(),
              entity.getDefrostLeadTimeHours(),
              entity.getSourceRecipeId());
    }
    return new InventoryItemDto(
        entity.getId(),
        entity.getUserId(),
        entity.getName(),
        entity.getCategory(),
        entity.getStorageLocation(),
        entity.getTrackingMode(),
        entity.getQuantity(),
        entity.getUnit(),
        entity.getCostPaid(),
        entity.getStatus(),
        entity.isStaple(),
        entity.getExpiryDate(),
        entity.getIngredientMappingKey(),
        entity.getNotes(),
        entity.getSource(),
        entity.getSourceRef(),
        entity.getItemStatus(),
        freezerExtension,
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }
}
