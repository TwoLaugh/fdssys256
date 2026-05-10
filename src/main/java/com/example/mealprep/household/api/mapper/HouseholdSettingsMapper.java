package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import org.mapstruct.Mapper;

/** Household-settings entity ↔ DTO mapping; the JSONB document is passed through unchanged. */
@Mapper(componentModel = "spring")
public interface HouseholdSettingsMapper {

  default HouseholdSettingsDto toDto(HouseholdSettings entity) {
    if (entity == null) {
      return null;
    }
    return new HouseholdSettingsDto(
        entity.getId(),
        entity.getHouseholdId(),
        entity.getDocument(),
        entity.getVersion(),
        entity.getCreatedAt());
  }
}
