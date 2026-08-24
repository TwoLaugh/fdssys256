package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.notification.api.dto.CreateNotificationRequest;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import java.util.UUID;

/**
 * Package-private working type produced by {@code NotificationKindResolver} and consumed by {@code
 * NotificationDispatcher}. Same shape as {@link CreateNotificationRequest} plus the originating
 * event's metric tag and origin carry-through.
 *
 * @param bundlingKey the kind-specific dedup key (e.g. {@code mealSlotId}, {@code planId}); null
 *     means dedup on {@code (userId, kind)} alone.
 * @param metricTag short stable tag for the {@code notification.dispatch.*} metrics (the kind
 *     name).
 */
record NotificationDraft(
    UUID userId,
    UUID householdId,
    NotificationKind kind,
    NotificationSeverity severity,
    String title,
    String body,
    NotificationPayload payload,
    String actionTargetUri,
    UUID sourceEventId,
    UUID traceId,
    String bundlingKey,
    Origin origin,
    String originTrace,
    String metricTag) {

  NotificationDraft {
    // A draft always targets a concrete user. The resolver returns empty when nobody resolves,
    // so a null here is a programming error, caught before it becomes a DB constraint violation.
    java.util.Objects.requireNonNull(userId, "userId");
  }

  CreateNotificationRequest toCreateRequest() {
    return new CreateNotificationRequest(
        userId,
        householdId,
        kind,
        severity,
        title,
        body,
        payload,
        actionTargetUri,
        sourceEventId,
        traceId,
        bundlingKey);
  }
}
