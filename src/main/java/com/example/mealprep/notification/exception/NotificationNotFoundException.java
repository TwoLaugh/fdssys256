package com.example.mealprep.notification.exception;

import java.util.UUID;

/** A notification was not found for the given id (scoped to the calling user). Maps to 404. */
public class NotificationNotFoundException extends NotificationException {

  public NotificationNotFoundException(UUID notificationId) {
    super("Notification not found: " + notificationId);
  }
}
