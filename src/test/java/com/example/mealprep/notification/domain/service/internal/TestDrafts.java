package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import java.util.UUID;

/**
 * Test-support factory living in the {@code internal} package so unit tests can build the
 * package-private {@link NotificationDraft} working type.
 */
public final class TestDrafts {

  private TestDrafts() {}

  public static NotificationDraft create(
      NotificationKind kind,
      String bundlingKey,
      NotificationSeverity severity,
      NotificationPayload payload) {
    return new NotificationDraft(
        UUID.randomUUID(),
        null,
        kind,
        severity,
        kind.name() + " title",
        kind.name() + " body",
        payload,
        "/app/x",
        UUID.randomUUID(),
        UUID.randomUUID(),
        bundlingKey,
        Origin.USER,
        null,
        kind.name());
  }

  public static NotificationDraft create(
      UUID userId,
      NotificationKind kind,
      NotificationSeverity severity,
      String bundlingKey,
      NotificationPayload payload) {
    return new NotificationDraft(
        userId,
        null,
        kind,
        severity,
        kind.name() + " title",
        kind.name() + " body",
        payload,
        "/app/x",
        UUID.randomUUID(),
        UUID.randomUUID(),
        bundlingKey,
        Origin.USER,
        null,
        kind.name());
  }
}
