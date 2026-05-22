package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/** Read shape of a user's notification preferences. */
public record NotificationPreferenceDto(
    UUID id,
    UUID userId,
    Map<NotificationKind, Boolean> enabledKinds,
    boolean quietHoursEnabled,
    LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    String timezone,
    int debounceWindowMinutes,
    long version) {}
