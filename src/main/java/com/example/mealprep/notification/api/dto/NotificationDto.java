package com.example.mealprep.notification.api.dto;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read shape of a notification. {@code version} is the JPA {@code @Version} value. */
public record NotificationDto(
    UUID id,
    UUID userId,
    UUID householdId,
    NotificationKind kind,
    NotificationSeverity severity,
    String title,
    String body,
    NotificationPayload payload,
    NotificationStatus status,
    String actionTargetUri,
    int bundleCount,
    List<String> bundleKeys,
    UUID traceId,
    Instant createdAt,
    Instant readAt,
    Instant actionedAt,
    Instant dismissedAt,
    long version) {}
