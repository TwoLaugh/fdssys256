package com.example.mealprep.provisions.api.mapper;

import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.provisions.domain.entity.Equipment;
import org.mapstruct.Mapper;

/** Equipment entity ↔ DTO mapping. */
@Mapper(componentModel = "spring")
public interface EquipmentMapper {

  default EquipmentDto toDto(Equipment entity) {
    if (entity == null) {
      return null;
    }
    return new EquipmentDto(
        entity.getId(),
        entity.getUserId(),
        entity.getName(),
        entity.isAvailable(),
        entity.getDetails(),
        entity.getVersion());
  }
}
