package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import java.util.UUID;

/**
 * Internal create request — listener → {@code NotificationUpdateService.create()}. Never bound to a
 * REST endpoint: exposing it would invite fake data and break the dispatcher's single-producer
 * assumption for debouncing.
 */
public record CreateNotificationRequest(
    UUID userId,
    UUID householdId,
    NotificationKind kind,
    NotificationSeverity severity,
    String title,
    String body,
    NotificationPayload payload,
    String actionTargetUri,
    UUID sourceEventId,
    UUID traceId) {}
