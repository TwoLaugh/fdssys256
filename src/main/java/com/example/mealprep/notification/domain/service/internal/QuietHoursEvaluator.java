package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.domain.entity.DeliverySkipReason;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a draft should be delivered given the user's preference row, applying the
 * preference toggle and the quiet-hours window. URGENT bypasses quiet hours (but not the preference
 * toggle). Wrap-around windows (e.g. 22:00 → 06:00) are handled.
 *
 * <p>Per {@code lld/notification.md} §F8:
 *
 * <ol>
 *   <li>{@code enabled_kinds[kind] == false} → SKIP(DISABLED_BY_PREF)
 *   <li>{@code severity == URGENT} → DELIVER (urgent bypasses quiet hours)
 *   <li>{@code quiet_hours_enabled == false} → DELIVER
 *   <li>{@code now} (in the preference's zone) inside {@code [start, end)} with wrap-around →
 *       SKIP(QUIET_HOURS)
 * </ol>
 */
@Component
public class QuietHoursEvaluator {

  private static final Logger log = LoggerFactory.getLogger(QuietHoursEvaluator.class);

  private final Clock clock;

  public QuietHoursEvaluator(Clock clock) {
    this.clock = clock;
  }

  /** Decision returned by {@link #evaluate}. {@code deliver=true} means no suppression. */
  public record Decision(boolean deliver, DeliverySkipReason skipReason) {
    static Decision allow() {
      return new Decision(true, null);
    }

    static Decision suppress(DeliverySkipReason reason) {
      return new Decision(false, reason);
    }
  }

  public Decision evaluate(
      NotificationKind kind, NotificationSeverity severity, NotificationPreference preference) {
    return evaluate(kind, severity, preference, Instant.now(clock));
  }

  /** Overload taking an explicit {@code now} — used by tests for deterministic time. */
  public Decision evaluate(
      NotificationKind kind,
      NotificationSeverity severity,
      NotificationPreference preference,
      Instant now) {
    Boolean enabled =
        preference.getEnabledKinds() == null ? null : preference.getEnabledKinds().get(kind);
    if (enabled != null && !enabled) {
      return Decision.suppress(DeliverySkipReason.DISABLED_BY_PREF);
    }
    if (severity == NotificationSeverity.URGENT) {
      return Decision.allow();
    }
    if (!preference.isQuietHoursEnabled()) {
      return Decision.allow();
    }
    LocalTime start = preference.getQuietHoursStart();
    LocalTime end = preference.getQuietHoursEnd();
    if (start == null || end == null) {
      // Enabled but no window — fail open (deliver). Validation prevents this on the write path.
      return Decision.allow();
    }
    LocalTime localNow = nowInZone(preference.getTimezone(), now);
    if (isWithinWindow(localNow, start, end)) {
      return Decision.suppress(DeliverySkipReason.QUIET_HOURS);
    }
    return Decision.allow();
  }

  private LocalTime nowInZone(String timezone, Instant now) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      log.warn("invalid timezone on preference, falling back to UTC timezone={}", timezone);
      zone = ZoneId.of("UTC");
    }
    return now.atZone(zone).toLocalTime();
  }

  /**
   * Half-open window {@code [start, end)} with wrap-around. For a non-wrapping window ({@code start
   * < end}) the time must be {@code >= start && < end}. For a wrap-around window ({@code start >
   * end}, e.g. 22:00 → 06:00) the time is inside if it is {@code >= start} OR {@code < end}.
   */
  static boolean isWithinWindow(LocalTime now, LocalTime start, LocalTime end) {
    if (start.isBefore(end)) {
      return !now.isBefore(start) && now.isBefore(end);
    }
    // Wrap-around: start >= end (equal is rejected at validation time).
    return !now.isBefore(start) || now.isBefore(end);
  }
}
