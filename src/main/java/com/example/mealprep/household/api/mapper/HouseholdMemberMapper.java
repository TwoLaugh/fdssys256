package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import org.mapstruct.Mapper;

/**
 * Member entity ↔ DTO mapping. {@code username} is not an entity field — it is joined from the auth
 * module by the caller (see {@code HouseholdServiceImpl}) and passed in explicitly.
 */
@Mapper(componentModel = "spring")
public interface HouseholdMemberMapper {

  default HouseholdMemberDto toDto(HouseholdMember entity, String username) {
    if (entity == null) {
      return null;
    }
    return new HouseholdMemberDto(
        entity.getId(),
        entity.getHousehold() == null ? null : entity.getHousehold().getId(),
        entity.getUserId(),
        entity.getRole(),
        entity.getDisplayName(),
        username,
        entity.getPriority(),
        entity.getJoinedAt(),
        entity.getVersion());
  }
}
