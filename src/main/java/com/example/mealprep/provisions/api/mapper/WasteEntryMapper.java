package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.WasteEntryDto;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import org.mapstruct.Mapper;

/** Maps {@link WasteEntry} → {@link WasteEntryDto}. Append-only; no reverse mapping. */
@Mapper(componentModel = "spring")
public interface WasteEntryMapper {

  default WasteEntryDto toDto(WasteEntry entity) {
    if (entity == null) {
      return null;
    }
    return new WasteEntryDto(
        entity.getId(),
        entity.getUserId(),
        entity.getInventoryItemId(),
        entity.getItemName(),
        entity.getQuantity(),
        entity.getUnit(),
        entity.getReason(),
        entity.getCostEstimate(),
        entity.getOccurredOn(),
        entity.getNotes(),
        entity.getCreatedAt());
  }
}
