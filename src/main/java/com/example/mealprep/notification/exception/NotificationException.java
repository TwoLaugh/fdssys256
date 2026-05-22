package com.example.mealprep.notification.exception;

/**
 * Module-root exception for the notification module. Per-failure subclasses extend this so the
 * module-specific {@code NotificationExceptionHandler} can map either the specific subtype
 * (preferred) or the root if a future subtype is added without a corresponding handler.
 */
public class NotificationException extends RuntimeException {

  public NotificationException(String message) {
    super(message);
  }

  public NotificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
