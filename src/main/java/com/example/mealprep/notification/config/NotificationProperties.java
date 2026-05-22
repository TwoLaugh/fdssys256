package com.example.mealprep.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the notification module — bound to the {@code
 * mealprep.notification.*} prefix. Defaults match {@code lld/notification.md}; every field falls
 * back to its documented default when the property is absent.
 *
 * <ul>
 *   <li>{@code retentionAfterRead} — placeholder for a future retention sweep ticket (default 90d);
 *   <li>{@code defaultDebounceWindowMinutes} — seed value for a new user's preference row;
 *   <li>{@code planGeneratedDefaultEnabled} — whether {@code PLANNER_PLAN_GENERATED} defaults ON.
 * </ul>
 */
@ConfigurationProperties(prefix = "mealprep.notification")
public record NotificationProperties(
    Duration retentionAfterRead,
    Integer defaultDebounceWindowMinutes,
    Boolean planGeneratedDefaultEnabled) {

  public NotificationProperties {
    if (retentionAfterRead == null) {
      retentionAfterRead = Duration.ofDays(90);
    }
    if (defaultDebounceWindowMinutes == null) {
      defaultDebounceWindowMinutes = 30;
    }
    if (planGeneratedDefaultEnabled == null) {
      planGeneratedDefaultEnabled = false;
    }
  }
}
