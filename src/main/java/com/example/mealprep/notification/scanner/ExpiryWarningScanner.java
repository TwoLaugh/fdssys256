package com.example.mealprep.notification.scanner;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.domain.repository.ExpiryWarningDispatchLogRepository;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.ScannerSupport;
import com.example.mealprep.notification.scanner.internal.entity.ExpiryWarningDispatchLog;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily scanner (06:00 by default) that warns users about inventory items approaching their expiry
 * date. Per {@code tickets/notification/01b-scanners.md} §Scanner #1.
 *
 * <p>For each user with active inventory it loads the items whose {@code expiryDate} is within the
 * widest per-location threshold, then applies the precise fridge / freezer / pantry cut-off (the
 * provisions {@code CUPBOARD} and {@code SPICE_RACK} locations both map to the "pantry" threshold).
 * It fires <strong>one</strong> {@code ItemNearingExpiryEvent} per user-batch; the notification
 * listener's bundling does any further collapsing.
 *
 * <p>Idempotent within a day via {@link ExpiryWarningDispatchLogRepository}: a {@code (userId,
 * scanDate)} row is written when the warning fires, and a second same-day scan is a no-op for that
 * user. The {@code @Transactional} boundary commits the log row + publishes the event together, so
 * the listener's {@code AFTER_COMMIT} dispatch runs only on a real commit.
 *
 * <p>Failure isolation: an exception fetching one user's data is caught, logged and counted; the
 * scan continues with the next user.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.notification.scanners",
    name = "expiry-warning.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ExpiryWarningScanner extends ScannerSupport {

  private final ProvisionQueryService provisionQueryService;
  private final HouseholdQueryService householdQueryService;
  private final ExpiryWarningDispatchLogRepository dispatchLogRepository;
  private final ScannerProperties properties;

  public ExpiryWarningScanner(
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      ProvisionQueryService provisionQueryService,
      HouseholdQueryService householdQueryService,
      ExpiryWarningDispatchLogRepository dispatchLogRepository,
      ScannerProperties properties) {
    super(clock, eventPublisher);
    this.provisionQueryService = provisionQueryService;
    this.householdQueryService = householdQueryService;
    this.dispatchLogRepository = dispatchLogRepository;
    this.properties = properties;
  }

  /** Scheduled trigger — daily 06:00 by default. The cron is far-future in the test profile. */
  @Scheduled(cron = "${mealprep.notification.scanners.expiry-warning.cron:0 0 6 * * ?}")
  @Transactional
  public int runScheduled() {
    return scan();
  }

  /**
   * Single synchronous scan; returns the number of users for whom a warning fired. Re-running the
   * same day is a no-op (the idempotency row excludes already-warned users).
   */
  @Transactional
  public int scan() {
    Instant now = now();
    ZoneId zone = clock().getZone();
    LocalDate today = LocalDate.ofInstant(now, zone);
    ScannerProperties.ExpiryWarning cfg = properties.expiryWarning();
    int widestThreshold = Math.max(cfg.fridgeDays(), Math.max(cfg.freezerDays(), cfg.pantryDays()));
    LocalDate maxExpiry = today.plusDays(widestThreshold);

    int fired = 0;
    for (UUID userId : provisionQueryService.getUserIdsWithActiveInventory()) {
      try {
        if (dispatchLogRepository.existsByUserIdAndScanDate(userId, today)) {
          continue;
        }
        // Single read; the result is ordered by expiry ASC so the first relevant item is the
        // earliest. Apply the precise per-location threshold in code.
        List<UUID> relevant = new ArrayList<>();
        LocalDate earliestExpiry = null;
        for (InventoryItemDto item :
            provisionQueryService.getExpiringInventory(userId, maxExpiry)) {
          if (item.expiryDate() == null) {
            continue;
          }
          long daysUntil = ChronoUnit.DAYS.between(today, item.expiryDate());
          if (daysUntil <= thresholdFor(item.storageLocation(), cfg)) {
            relevant.add(item.id());
            if (earliestExpiry == null) {
              earliestExpiry = item.expiryDate();
            }
          }
        }
        if (relevant.isEmpty()) {
          continue;
        }
        UUID householdId = householdIdFor(userId);
        UUID traceId = UUID.randomUUID();
        publish(
            new com.example.mealprep.provisions.event.ItemNearingExpiryEvent(
                userId, householdId, relevant, earliestExpiry, traceId, now));
        dispatchLogRepository.save(
            ExpiryWarningDispatchLog.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .scanDate(today)
                .firedAt(now)
                .itemCount(relevant.size())
                .build());
        fired++;
      } catch (RuntimeException e) {
        log.error("expiry-warning scan failed for userId={}", userId, e);
      }
    }
    log.info("expiry-warning scan complete: firedForUsers={} scanDate={}", fired, today);
    return fired;
  }

  private static int thresholdFor(StorageLocation location, ScannerProperties.ExpiryWarning cfg) {
    return switch (location) {
      case FRIDGE -> cfg.fridgeDays();
      case FREEZER -> cfg.freezerDays();
        // CUPBOARD + SPICE_RACK are the "pantry" of provision-model.md.
      case CUPBOARD, SPICE_RACK -> cfg.pantryDays();
    };
  }

  private UUID householdIdFor(UUID userId) {
    return householdQueryService.getByUserId(userId).map(HouseholdDto::id).orElse(null);
  }
}
