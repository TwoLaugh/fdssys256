package com.example.mealprep.preference.api.mapper;

import com.example.mealprep.preference.api.dto.LifestyleConfigAuditEntryDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.entity.LifestyleConfig;
import com.example.mealprep.preference.domain.entity.LifestyleConfigAuditLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for the lifestyle-config aggregate. Hand-written {@code default} methods
 * (rather than MapStruct field-matching) keep the mapping explicit and avoid a generated mapper for
 * the audit-row case where the {@link LifestyleConfigAuditLog#getLifestyleConfig()} association is
 * intentionally NOT projected — only {@code lifestyleConfigId} is sometimes needed at the DTO
 * layer, and the DTO carries no such field today.
 */
@Mapper(componentModel = "spring")
public interface LifestyleConfigMapper {

  default LifestyleConfigDto toDto(LifestyleConfig entity) {
    if (entity == null) {
      return null;
    }
    return new LifestyleConfigDto(
        entity.getId(),
        entity.getUserId(),
        entity.getDocument(),
        entity.getLastReviewPromptAt(),
        entity.getOptimisticVersion(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  default LifestyleConfigAuditEntryDto toAuditEntryDto(LifestyleConfigAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new LifestyleConfigAuditEntryDto(
        entity.getId(),
        entity.getActorUserId(),
        entity.getFieldPath(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }

  default List<LifestyleConfigDto> toDtos(List<LifestyleConfig> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<LifestyleConfigDto> result = new ArrayList<>(entities.size());
    for (LifestyleConfig e : entities) {
      result.add(toDto(e));
    }
    return result;
  }

  default List<LifestyleConfigAuditEntryDto> toAuditEntryDtos(
      List<LifestyleConfigAuditLog> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<LifestyleConfigAuditEntryDto> result = new ArrayList<>(entities.size());
    for (LifestyleConfigAuditLog e : entities) {
      result.add(toAuditEntryDto(e));
    }
    return result;
  }
}
