package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.entity.DeliverySkipReason;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPreference;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.domain.service.internal.QuietHoursEvaluator;
import com.example.mealprep.notification.testdata.NotificationTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuietHoursEvaluatorTest {

  private final QuietHoursEvaluator evaluator = new QuietHoursEvaluator(Clock.systemUTC());

  /** A UTC Instant for the given Europe/London wall-clock time on an arbitrary winter date. */
  private static Instant londonInstant(String localTime) {
    return LocalDate.of(2026, 1, 15)
        .atTime(LocalTime.parse(localTime))
        .atZone(ZoneId.of("Europe/London"))
        .toInstant();
  }

  @Test
  void evaluate_disabledKind_skipsWithDisabledByPref() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.preference(user);
    pref.getEnabledKinds().put(NotificationKind.PROVISION_ITEM_NEAR_EXPIRY, false);

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            Instant.now());

    assertThat(decision.deliver()).isFalse();
    assertThat(decision.skipReason()).isEqualTo(DeliverySkipReason.DISABLED_BY_PREF);
  }

  @Test
  void evaluate_quietHours2330Local_suppresses() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            londonInstant("23:30"));

    assertThat(decision.deliver()).isFalse();
    assertThat(decision.skipReason()).isEqualTo(DeliverySkipReason.QUIET_HOURS);
  }

  @Test
  void evaluate_0700LocalOutsideWindow_delivers() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            londonInstant("07:00"));

    assertThat(decision.deliver()).isTrue();
  }

  @Test
  void evaluate_urgentInsideQuietHours_bypasses() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");

    var decision =
        evaluator.evaluate(
            NotificationKind.HEALTH_DIRECTIVE_RECEIVED,
            NotificationSeverity.URGENT,
            pref,
            londonInstant("23:30"));

    assertThat(decision.deliver()).isTrue();
  }

  @Test
  void evaluate_quietHoursDisabled_delivers() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.preference(user);

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            Instant.now());

    assertThat(decision.deliver()).isTrue();
  }

  @Test
  void evaluate_wrapAroundEarlyMorning_suppresses() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            londonInstant("02:00"));

    assertThat(decision.deliver()).isFalse();
  }

  @Test
  void evaluate_timezoneResolution_differsByZone() {
    UUID user = UUID.randomUUID();
    // Same Instant: 23:30 London winter == 15:30 America/Los_Angeles.
    Instant sameInstant = londonInstant("23:30");

    NotificationPreference london =
        NotificationTestData.quietHoursPreference(user, "22:00", "06:00");
    NotificationPreference la = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");
    la.setTimezone(ZoneId.of("America/Los_Angeles").getId());

    assertThat(
            evaluator
                .evaluate(
                    NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
                    NotificationSeverity.ATTENTION,
                    london,
                    sameInstant)
                .deliver())
        .isFalse();
    assertThat(
            evaluator
                .evaluate(
                    NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
                    NotificationSeverity.ATTENTION,
                    la,
                    sameInstant)
                .deliver())
        .isTrue();
  }

  @Test
  void evaluate_invalidTimezoneFallsBackToUtc() {
    UUID user = UUID.randomUUID();
    NotificationPreference pref = NotificationTestData.quietHoursPreference(user, "22:00", "06:00");
    pref.setTimezone("Mars/Phobos");
    // 23:30 UTC is inside 22:00-06:00 → suppressed under the UTC fallback.
    Instant utc2330 = LocalDate.of(2026, 1, 15).atTime(23, 30).toInstant(ZoneOffset.UTC);

    var decision =
        evaluator.evaluate(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            NotificationSeverity.ATTENTION,
            pref,
            utc2330);

    assertThat(decision.deliver()).isFalse();
  }
}
