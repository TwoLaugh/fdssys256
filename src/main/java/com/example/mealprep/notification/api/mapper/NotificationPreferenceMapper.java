package com.example.mealprep.notification.api.mapper;

import com.example.mealprep.notification.api.dto.NotificationPreferenceDto;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import java.util.EnumMap;
import java.util.Map;
import org.mapstruct.Mapper;

/** {@link NotificationPreference} → {@link NotificationPreferenceDto} mapping. */
@Mapper(componentModel = "spring")
public interface NotificationPreferenceMapper {

  default NotificationPreferenceDto toDto(NotificationPreference entity) {
    if (entity == null) {
      return null;
    }
    return new NotificationPreferenceDto(
        entity.getId(),
        entity.getUserId(),
        defensiveCopy(entity.getEnabledKinds()),
        entity.isQuietHoursEnabled(),
        entity.getQuietHoursStart(),
        entity.getQuietHoursEnd(),
        entity.getTimezone(),
        entity.getDebounceWindowMinutes(),
        entity.getOptimisticVersion());
  }

  private static Map<NotificationKind, Boolean> defensiveCopy(
      Map<NotificationKind, Boolean> source) {
    if (source == null || source.isEmpty()) {
      return new EnumMap<>(NotificationKind.class);
    }
    return new EnumMap<>(source);
  }
}
