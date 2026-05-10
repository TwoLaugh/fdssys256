package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.domain.entity.DailyActivityLog;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for {@link DailyActivityLog}. */
@Mapper(componentModel = "spring")
public interface DailyActivityMapper {

  default DailyActivityDto toDto(DailyActivityLog entity) {
    if (entity == null) {
      return null;
    }
    return new DailyActivityDto(
        entity.getId(),
        entity.getUserId(),
        entity.getOnDate(),
        entity.getActivityLevel(),
        entity.getNotes(),
        entity.getCreatedAt());
  }
}
