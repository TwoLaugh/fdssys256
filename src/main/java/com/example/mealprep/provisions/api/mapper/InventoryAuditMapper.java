package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.InventoryAuditEntryDto;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import org.mapstruct.Mapper;

/** Inventory audit-log entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface InventoryAuditMapper {

  default InventoryAuditEntryDto toDto(InventoryAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new InventoryAuditEntryDto(
        entity.getId(),
        entity.getInventoryItemId(),
        entity.getActor(),
        entity.getActorUserId(),
        entity.getFieldChanged(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }
}
