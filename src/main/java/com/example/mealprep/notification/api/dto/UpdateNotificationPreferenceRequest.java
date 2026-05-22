package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.validation.ValidQuietHours;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.Map;

/**
 * Request shape for {@code PUT /api/v1/notifications/preferences}. {@link ValidQuietHours} is a
 * class-level constraint that cross-checks the quiet-hours fields and validates the timezone.
 */
@ValidQuietHours
public record UpdateNotificationPreferenceRequest(
    @NotNull Map<@NotNull NotificationKind, @NotNull Boolean> enabledKinds,
    boolean quietHoursEnabled,
    LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    @NotBlank String timezone,
    @Min(0) @Max(360) int debounceWindowMinutes,
    long expectedVersion) {}
