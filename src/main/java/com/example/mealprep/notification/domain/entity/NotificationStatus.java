package com.example.mealprep.notification.domain.entity;

/**
 * Lifecycle status of a notification. The legal state machine (enforced in {@code
 * NotificationServiceImpl}) is: {@code UNREAD → READ | DISMISSED | ACTIONED}, {@code READ →
 * DISMISSED | ACTIONED}, {@code ACTIONED → DISMISSED}, {@code DISMISSED → (terminal)}.
 */
public enum NotificationStatus {
  UNREAD,
  READ,
  DISMISSED,
  ACTIONED
}
