package com.example.mealprep.notification.domain.service;

import com.example.mealprep.notification.api.dto.CreateNotificationRequest;
import com.example.mealprep.notification.api.dto.NotificationDto;
import com.example.mealprep.notification.api.dto.NotificationPreferenceDto;
import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import java.util.Set;
import java.util.UUID;

/**
 * Public write API for the notification module. {@link #create(CreateNotificationRequest)} is
 * listener-facing (invoked by the dispatcher) and lives on the public interface so it is directly
 * seam-testable; it is never bound to a REST endpoint.
 */
public interface NotificationUpdateService {

  /** Persist a new notification. Listener-facing (via the dispatcher); never bound to REST. */
  NotificationDto create(CreateNotificationRequest request);

  NotificationDto markRead(UUID userId, UUID notificationId);

  NotificationDto markDismissed(UUID userId, UUID notificationId);

  NotificationDto markActioned(UUID userId, UUID notificationId);

  /** Mark all of a user's unread notifications read; an empty {@code kinds} set means all kinds. */
  int markAllRead(UUID userId, Set<NotificationKind> kinds);

  NotificationPreferenceDto updatePreferences(
      UUID userId, UpdateNotificationPreferenceRequest request);

  /** Idempotent — creates a defaults row on first call, returns the existing row thereafter. */
  NotificationPreferenceDto ensurePreferencesForUser(UUID userId);
}
