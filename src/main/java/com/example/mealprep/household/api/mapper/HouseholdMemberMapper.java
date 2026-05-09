package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import org.mapstruct.Mapper;

/** Member entity ↔ DTO mapping. */
@Mapper(componentModel = "spring")
public interface HouseholdMemberMapper {

  default HouseholdMemberDto toDto(HouseholdMember entity) {
    if (entity == null) {
      return null;
    }
    return new HouseholdMemberDto(
        entity.getId(),
        entity.getHousehold() == null ? null : entity.getHousehold().getId(),
        entity.getUserId(),
        entity.getRole(),
        entity.getDisplayName(),
        entity.getPriority(),
        entity.getJoinedAt(),
        entity.getVersion());
  }
}
