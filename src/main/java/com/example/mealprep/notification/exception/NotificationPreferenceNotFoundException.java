package com.example.mealprep.notification.exception;

import java.util.UUID;

/** No notification-preference row exists for the given user. Maps to 404. */
public class NotificationPreferenceNotFoundException extends NotificationException {

  public NotificationPreferenceNotFoundException(UUID userId) {
    super("Notification preferences not found for user: " + userId);
  }
}
