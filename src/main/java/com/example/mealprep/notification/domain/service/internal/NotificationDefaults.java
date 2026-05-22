package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for the default per-kind enabled map seeded into a new user's preference
 * row (see the repeatable migration's header note for why this lives in code, not a seed table).
 * Every kind defaults ON except {@code PLANNER_PLAN_GENERATED}, which is default OFF per {@code
 * lld/notification.md}.
 */
public final class NotificationDefaults {

  private NotificationDefaults() {}

  /** Default debounce window for a new user, in minutes. */
  public static final int DEFAULT_DEBOUNCE_WINDOW_MINUTES = 30;

  /** Default timezone for a new user. */
  public static final String DEFAULT_TIMEZONE = "Europe/London";

  /**
   * Build the default enabled-kinds map. {@code planGeneratedDefaultEnabled} overrides the toggle
   * for {@code PLANNER_PLAN_GENERATED} (configurable via {@code NotificationProperties}).
   */
  public static Map<NotificationKind, Boolean> defaultEnabledKinds(
      boolean planGeneratedDefaultEnabled) {
    Map<NotificationKind, Boolean> map = new EnumMap<>(NotificationKind.class);
    for (NotificationKind kind : NotificationKind.values()) {
      map.put(kind, true);
    }
    map.put(NotificationKind.PLANNER_PLAN_GENERATED, planGeneratedDefaultEnabled);
    return map;
  }
}
