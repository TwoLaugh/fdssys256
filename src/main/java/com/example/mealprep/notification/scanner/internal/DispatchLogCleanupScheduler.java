package com.example.mealprep.notification.scanner.internal;

import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.repository.DefrostReminderDispatchLogRepository;
import com.example.mealprep.notification.scanner.internal.repository.ExpiryWarningDispatchLogRepository;
import com.example.mealprep.notification.scanner.internal.repository.NutritionAlertDispatchLogRepository;
import com.example.mealprep.notification.scanner.internal.repository.PrepReminderDispatchLogRepository;
import com.example.mealprep.notification.scanner.internal.repository.StapleReplenishmentDispatchLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily retention sweep over the five scanner idempotency-log tables (notification/01b §27).
 * Deletes rows whose {@code fired_at} precedes {@code now − dispatchLogRetentionDays} (default 30)
 * so the dispatch logs stay bounded. Reads the injected {@link Clock} so tests pin the cutoff via
 * {@code Clock.fixed}; the cron is far-future in the test profile.
 *
 * <p>This is a maintenance scheduler, not a notification producer — it deliberately does not extend
 * {@code ScannerSupport} (it publishes no events).
 */
@Component
public class DispatchLogCleanupScheduler {

  private static final Logger log = LoggerFactory.getLogger(DispatchLogCleanupScheduler.class);

  private final ExpiryWarningDispatchLogRepository expiryWarning;
  private final DefrostReminderDispatchLogRepository defrostReminder;
  private final PrepReminderDispatchLogRepository prepReminder;
  private final NutritionAlertDispatchLogRepository nutritionAlert;
  private final StapleReplenishmentDispatchLogRepository stapleReplenishment;
  private final ScannerProperties properties;
  private final Clock clock;

  DispatchLogCleanupScheduler(
      ExpiryWarningDispatchLogRepository expiryWarning,
      DefrostReminderDispatchLogRepository defrostReminder,
      PrepReminderDispatchLogRepository prepReminder,
      NutritionAlertDispatchLogRepository nutritionAlert,
      StapleReplenishmentDispatchLogRepository stapleReplenishment,
      ScannerProperties properties,
      Clock clock) {
    this.expiryWarning = expiryWarning;
    this.defrostReminder = defrostReminder;
    this.prepReminder = prepReminder;
    this.nutritionAlert = nutritionAlert;
    this.stapleReplenishment = stapleReplenishment;
    this.properties = properties;
    this.clock = clock;
  }

  /** Daily 02:00 by default; override via {@code mealprep.notification.scanners.cleanup.cron}. */
  @Scheduled(cron = "${mealprep.notification.scanners.cleanup.cron:0 0 2 * * ?}")
  @Transactional
  public int runScheduled() {
    return sweep();
  }

  /** Single synchronous sweep; returns the total number of dispatch-log rows deleted. */
  @Transactional
  public int sweep() {
    Instant cutoff =
        Instant.now(clock).minus(Duration.ofDays(properties.dispatchLogRetentionDays()));
    int deleted =
        expiryWarning.deleteByFiredAtBefore(cutoff)
            + defrostReminder.deleteByFiredAtBefore(cutoff)
            + prepReminder.deleteByFiredAtBefore(cutoff)
            + nutritionAlert.deleteByFiredAtBefore(cutoff)
            + stapleReplenishment.deleteByFiredAtBefore(cutoff);
    if (deleted > 0) {
      log.info("dispatch-log cleanup deleted={} cutoff={}", deleted, cutoff);
    }
    return deleted;
  }
}
