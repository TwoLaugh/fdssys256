package com.example.mealprep.notification.exception;

import com.example.mealprep.notification.domain.entity.NotificationStatus;

/** An illegal notification status transition was attempted. Maps to 409. */
public class NotificationStateTransitionException extends NotificationException {

  private final NotificationStatus from;
  private final NotificationStatus to;

  public NotificationStateTransitionException(NotificationStatus from, NotificationStatus to) {
    super("Illegal notification status transition: " + from + " -> " + to);
    this.from = from;
    this.to = to;
  }

  public NotificationStatus getFrom() {
    return from;
  }

  public NotificationStatus getTo() {
    return to;
  }
}
