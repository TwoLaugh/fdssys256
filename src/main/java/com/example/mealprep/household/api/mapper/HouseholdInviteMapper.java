package com.example.mealprep.household.api.mapper;

import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.InviteStatus;
import com.example.mealprep.household.domain.entity.HouseholdInvite;
import java.time.Instant;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Invite entity ↔ DTO mapping. Two flavours:
 *
 * <ul>
 *   <li>{@link #toDtoWithCode} — used by the create response, which is the only path that surfaces
 *       the {@code inviteCode}.
 *   <li>{@link #toDtoCodeRedacted} — used by list / accept responses; sets {@code inviteCode =
 *       null}.
 * </ul>
 *
 * Status is derived from {@code acceptedAt} / {@code revokedAt} / {@code expiresAt} at mapping time
 * — never persisted on the entity.
 */
@Mapper(componentModel = "spring")
public interface HouseholdInviteMapper {

  /** Used by createInvite — code is included in the response. */
  @Mapping(target = "status", expression = "java(deriveStatus(entity))")
  HouseholdInviteDto toDtoWithCode(HouseholdInvite entity);

  /** Used by listPendingInvites — code is always null. */
  @Mapping(target = "inviteCode", ignore = true)
  @Mapping(target = "status", expression = "java(deriveStatus(entity))")
  HouseholdInviteDto toDtoCodeRedacted(HouseholdInvite entity);

  default List<HouseholdInviteDto> toDtosCodeRedacted(List<HouseholdInvite> es) {
    if (es == null) {
      return List.of();
    }
    return es.stream().map(this::toDtoCodeRedacted).toList();
  }

  default InviteStatus deriveStatus(HouseholdInvite e) {
    if (e == null) {
      return null;
    }
    if (e.getRevokedAt() != null) {
      return InviteStatus.REVOKED;
    }
    if (e.getAcceptedAt() != null) {
      return InviteStatus.ACCEPTED;
    }
    if (e.getExpiresAt() != null && e.getExpiresAt().isBefore(Instant.now())) {
      return InviteStatus.EXPIRED;
    }
    return InviteStatus.PENDING;
  }
}
