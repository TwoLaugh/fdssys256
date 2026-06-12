package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.Mapper;

/**
 * Household entity ↔ DTO mapping. Member sub-mapping is inlined rather than delegated to {@link
 * HouseholdMemberMapper} so this class has no MapStruct {@code uses=} wiring — kept consistent with
 * the {@code HardConstraintsMapper} pattern in the preference module.
 *
 * <p>{@code usernamesByUserId} carries the auth-side username join: the caller resolves all member
 * usernames in ONE batched auth read (no per-member N+1) and passes the map here. Members whose
 * userId is absent from the map (soft-deleted users) get a null {@code username}.
 */
@Mapper(componentModel = "spring")
public interface HouseholdMapper {

  default HouseholdDto toDto(Household entity, Map<UUID, String> usernamesByUserId) {
    if (entity == null) {
      return null;
    }
    return new HouseholdDto(
        entity.getId(),
        entity.getName(),
        entity.getCreatedByUserId(),
        mapMembers(entity.getMembers(), usernamesByUserId),
        entity.getCreatedAt(),
        entity.getVersion());
  }

  private static List<HouseholdMemberDto> mapMembers(
      List<HouseholdMember> members, Map<UUID, String> usernamesByUserId) {
    if (members == null || members.isEmpty()) {
      return Collections.emptyList();
    }
    Map<UUID, String> usernames =
        usernamesByUserId == null ? Collections.emptyMap() : usernamesByUserId;
    List<HouseholdMemberDto> result = new ArrayList<>(members.size());
    for (HouseholdMember member : members) {
      result.add(
          new HouseholdMemberDto(
              member.getId(),
              member.getHousehold() == null ? null : member.getHousehold().getId(),
              member.getUserId(),
              member.getRole(),
              member.getDisplayName(),
              usernames.get(member.getUserId()),
              member.getPriority(),
              member.getJoinedAt(),
              member.getVersion()));
    }
    return result;
  }
}
