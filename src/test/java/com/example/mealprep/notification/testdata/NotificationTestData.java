package com.example.mealprep.notification.testdata;

import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared fixtures for notification unit + integration tests. */
public final class NotificationTestData {

  private NotificationTestData() {}

  public static Map<NotificationKind, Boolean> allEnabled() {
    Map<NotificationKind, Boolean> map = new EnumMap<>(NotificationKind.class);
    for (NotificationKind kind : NotificationKind.values()) {
      map.put(kind, true);
    }
    return map;
  }

  public static NotificationPreference preference(UUID userId) {
    return NotificationPreference.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .enabledKinds(allEnabled())
        .quietHoursEnabled(false)
        .timezone("Europe/London")
        .debounceWindowMinutes(30)
        .optimisticVersion(0L)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  public static NotificationPreference quietHoursPreference(UUID userId, String start, String end) {
    NotificationPreference p = preference(userId);
    p.setQuietHoursEnabled(true);
    p.setQuietHoursStart(java.time.LocalTime.parse(start));
    p.setQuietHoursEnd(java.time.LocalTime.parse(end));
    return p;
  }

  public static Notification notification(
      UUID userId, NotificationKind kind, NotificationStatus status) {
    return Notification.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .kind(kind)
        .severity(NotificationSeverity.ATTENTION)
        .title("Test")
        .body("Test body")
        .payload(
            new NotificationPayload.ItemNearExpiryPayload(
                NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
                List.of(UUID.randomUUID()),
                LocalDate.now(),
                1))
        .status(status)
        .bundleCount(1)
        .createdAt(Instant.now())
        .optimisticVersion(0L)
        .build();
  }
}
