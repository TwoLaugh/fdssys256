package com.example.mealprep.notification.scanner;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.ScannerSupport;
import com.example.mealprep.notification.scanner.internal.entity.DefrostReminderDispatchLog;
import com.example.mealprep.notification.scanner.internal.repository.DefrostReminderDispatchLogRepository;
import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scanner (every 15 minutes by default) that reminds users to move a frozen item to defrost ahead
 * of its use-by. Per {@code tickets/notification/01b-scanners.md} §Scanner #2.
 *
 * <p>The defrost lead-time and the use-by anchor live in the provisions module (per {@code
 * design/provision-model.md} — "the data that drives [defrost scheduling] lives in Provisions"), so
 * the scanner reads the frozen-item candidates from {@code ProvisionQueryService}. For each
 * candidate it treats the item's {@code expiryDate} (start-of-day, in the clock's zone) as the meal
 * anchor and computes {@code defrostTargetTime = anchor − defrostLeadTimeHours}. When the current
 * time is within 1 hour of the target it fires a {@code DefrostReminderEvent}.
 *
 * <p>Idempotent per {@code (inventoryItemId, defrostTargetTime)} via {@link
 * DefrostReminderDispatchLogRepository} (the item id stands in for the slot id — provisions does
 * not carry a planner slot reference). The {@code @Transactional} boundary commits the log row and
 * publishes together so the listener's {@code AFTER_COMMIT} dispatch runs only on a real commit.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.notification.scanners",
    name = "defrost-reminder.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DefrostReminderScanner extends ScannerSupport {

  /** Half-width of the fire window: fire when |now − target| <= 1 hour. */
  private static final Duration FIRE_WINDOW = Duration.ofHours(1);

  private final ProvisionQueryService provisionQueryService;
  private final HouseholdQueryService householdQueryService;
  private final DefrostReminderDispatchLogRepository dispatchLogRepository;

  public DefrostReminderScanner(
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      ProvisionQueryService provisionQueryService,
      HouseholdQueryService householdQueryService,
      DefrostReminderDispatchLogRepository dispatchLogRepository,
      ScannerProperties properties) {
    super(clock, eventPublisher);
    this.provisionQueryService = provisionQueryService;
    this.householdQueryService = householdQueryService;
    this.dispatchLogRepository = dispatchLogRepository;
  }

  /**
   * Scheduled trigger — every 15 minutes by default. The cron is far-future in the test profile.
   */
  @Scheduled(cron = "${mealprep.notification.scanners.defrost-reminder.cron:0 */15 * * * ?}")
  @Transactional
  public int runScheduled() {
    return scan();
  }

  /** Single synchronous scan; returns the number of defrost reminders fired this run. */
  @Transactional
  public int scan() {
    Instant now = now();
    ZoneId zone = clock().getZone();
    int fired = 0;
    for (UUID userId : provisionQueryService.getUserIdsWithActiveInventory()) {
      try {
        for (InventoryItemDto item : provisionQueryService.getDefrostCandidates(userId)) {
          FreezerExtensionDto fx = item.freezerExtension();
          if (fx == null || fx.defrostLeadTimeHours() == null || item.expiryDate() == null) {
            continue;
          }
          Instant mealAnchor = item.expiryDate().atStartOfDay(zone).toInstant();
          Instant target = mealAnchor.minus(Duration.ofHours(fx.defrostLeadTimeHours()));
          if (Duration.between(now, target).abs().compareTo(FIRE_WINDOW) > 0) {
            continue;
          }
          if (dispatchLogRepository.existsBySlotIdAndDefrostTargetTime(item.id(), target)) {
            continue;
          }
          UUID householdId = householdIdFor(userId);
          UUID traceId = UUID.randomUUID();
          publish(
              new com.example.mealprep.provisions.event.DefrostReminderEvent(
                  userId, householdId, item.id(), null, target, traceId, now));
          dispatchLogRepository.save(
              DefrostReminderDispatchLog.builder()
                  .id(UUID.randomUUID())
                  .slotId(item.id())
                  .defrostTargetTime(target)
                  .userId(userId)
                  .firedAt(now)
                  .build());
          fired++;
        }
      } catch (RuntimeException e) {
        log.error("defrost-reminder scan failed for userId={}", userId, e);
      }
    }
    log.info("defrost-reminder scan complete: fired={}", fired);
    return fired;
  }

  private UUID householdIdFor(UUID userId) {
    return householdQueryService.getByUserId(userId).map(HouseholdDto::id).orElse(null);
  }
}
