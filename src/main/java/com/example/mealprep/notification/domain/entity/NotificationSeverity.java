package com.example.mealprep.notification.domain.entity;

/**
 * Severity tier of a notification. {@code URGENT} bypasses quiet hours (see {@code
 * QuietHoursEvaluator}); {@code INFO}/{@code ATTENTION} are subject to preference + quiet-hours
 * filtering.
 */
public enum NotificationSeverity {
  INFO,
  ATTENTION,
  URGENT
}
