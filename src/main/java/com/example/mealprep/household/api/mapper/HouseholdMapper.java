package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Household entity ↔ DTO mapping. Member sub-mapping is inlined rather than delegated to {@link
 * HouseholdMemberMapper} so this class has no MapStruct {@code uses=} wiring — kept consistent with
 * the {@code HardConstraintsMapper} pattern in the preference module.
 */
@Mapper(componentModel = "spring")
public interface HouseholdMapper {

  default HouseholdDto toDto(Household entity) {
    if (entity == null) {
      return null;
    }
    return new HouseholdDto(
        entity.getId(),
        entity.getName(),
        entity.getCreatedByUserId(),
        mapMembers(entity.getMembers()),
        entity.getCreatedAt(),
        entity.getVersion());
  }

  private static List<HouseholdMemberDto> mapMembers(List<HouseholdMember> members) {
    if (members == null || members.isEmpty()) {
      return Collections.emptyList();
    }
    List<HouseholdMemberDto> result = new ArrayList<>(members.size());
    for (HouseholdMember member : members) {
      result.add(
          new HouseholdMemberDto(
              member.getId(),
              member.getHousehold() == null ? null : member.getHousehold().getId(),
              member.getUserId(),
              member.getRole(),
              member.getDisplayName(),
              member.getPriority(),
              member.getJoinedAt(),
              member.getVersion()));
    }
    return result;
  }
}
