package com.example.mealprep.notification.scanner.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised per-scanner configuration bound to the {@code mealprep.notification.scanners.*}
 * prefix (notification/01b). Each nested record carries the scanner's {@code enabled} flag, its
 * cron expression and any threshold knobs. Defaults match {@code
 * tickets/notification/01b-scanners.md}; every field falls back to its documented default when the
 * property is absent so the binder never NPEs on a partially-specified profile.
 *
 * <p>Cron expressions are <em>not</em> read from here by the {@code @Scheduled} annotations
 * (annotation attributes must be compile-time constants) — the scanners reference the same property
 * keys via {@code ${...}} placeholders with identical defaults. This record drives the runtime
 * threshold + the enable/disable check (the latter also enforced structurally by
 * {@code @ConditionalOnProperty} on each scanner bean).
 */
@ConfigurationProperties(prefix = "mealprep.notification.scanners")
public record ScannerProperties(
    ExpiryWarning expiryWarning,
    DefrostReminder defrostReminder,
    PrepReminder prepReminder,
    NutritionAlert nutritionAlert,
    StapleReplenishment stapleReplenishment,
    Integer dispatchLogRetentionDays) {

  public ScannerProperties {
    if (expiryWarning == null) {
      expiryWarning = new ExpiryWarning(true, null, null, null, null);
    }
    if (defrostReminder == null) {
      defrostReminder = new DefrostReminder(true, null);
    }
    if (prepReminder == null) {
      prepReminder = new PrepReminder(true, null, null);
    }
    if (nutritionAlert == null) {
      nutritionAlert = new NutritionAlert(true, null, null);
    }
    if (stapleReplenishment == null) {
      stapleReplenishment = new StapleReplenishment(true, null);
    }
    if (dispatchLogRetentionDays == null) {
      dispatchLogRetentionDays = 30;
    }
  }

  /** Expiry-warning scanner config. {@code fridge/freezer/pantry} are day thresholds. */
  public record ExpiryWarning(
      boolean enabled, String cron, Integer fridgeDays, Integer freezerDays, Integer pantryDays) {
    public ExpiryWarning {
      if (fridgeDays == null) {
        fridgeDays = 2;
      }
      if (freezerDays == null) {
        freezerDays = 14;
      }
      if (pantryDays == null) {
        pantryDays = 7;
      }
    }
  }

  /** Defrost-reminder scanner config. */
  public record DefrostReminder(boolean enabled, String cron) {}

  /** Prep-reminder scanner config. {@code leadMinutes} = the half-width of the fire window. */
  public record PrepReminder(boolean enabled, String cron, Integer leadMinutes) {
    public PrepReminder {
      if (leadMinutes == null) {
        leadMinutes = 15;
      }
    }
  }

  /** Nutrition-alert scanner config. {@code threshold} = fractional divergence to alert at. */
  public record NutritionAlert(boolean enabled, String cron, BigDecimal threshold) {
    public NutritionAlert {
      if (threshold == null) {
        threshold = new BigDecimal("0.30");
      }
    }
  }

  /** Staple-replenishment scanner config. */
  public record StapleReplenishment(boolean enabled, String cron) {}
}
