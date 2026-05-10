package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import org.mapstruct.Mapper;

/** Household-settings audit-log entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface HouseholdSettingsAuditMapper {

  default HouseholdSettingsAuditEntryDto toDto(HouseholdSettingsAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new HouseholdSettingsAuditEntryDto(
        entity.getId(),
        entity.getActorUserId(),
        entity.getFieldPath(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }
}
