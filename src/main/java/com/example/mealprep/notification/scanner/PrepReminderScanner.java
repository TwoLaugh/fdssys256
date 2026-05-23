package com.example.mealprep.notification.scanner;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.domain.repository.PrepReminderDispatchLogRepository;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.ScannerSupport;
import com.example.mealprep.notification.scanner.internal.entity.PrepReminderDispatchLog;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scanner (every 5 minutes by default — finer-grained because the prep moment is a precise time)
 * that reminds users to start an advance-prep step for an upcoming planned meal. Per {@code
 * tickets/notification/01b-scanners.md} §Scanner #3.
 *
 * <p>The prep moment anchors on the slot's resolved wall-clock meal time, which the planner now
 * supplies on {@link UpcomingSlotView#mealTime()} (planner-01m — coalesced from the slot override,
 * the household owner's lifestyle-config schedule, then the slot-kind default; never null). When
 * the slot carries an explicit {@link UpcomingSlotView#prepStepAtTime()} override (reserved for the
 * future "pre-cook actions" feature; currently always null) the scanner fires relative to it;
 * otherwise it derives {@code prepStepAtTime = dayDate@mealTime − timeBudgetMin}, evaluated in the
 * clock's zone. When the current time is within {@code leadMinutes} (default 15) of that moment, it
 * fires a {@code PrepReminderEvent}.
 *
 * <p>Users are enumerated via provisions; each user's household → active plan slots come from
 * {@link PlanQueryService#getUpcomingSlots}. Idempotent per {@code (slotId, prepStepAtTime)} via
 * {@link PrepReminderDispatchLogRepository}.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.notification.scanners",
    name = "prep-reminder.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PrepReminderScanner extends ScannerSupport {

  private final ProvisionQueryService provisionQueryService;
  private final HouseholdQueryService householdQueryService;
  private final PlanQueryService planQueryService;
  private final PrepReminderDispatchLogRepository dispatchLogRepository;
  private final ScannerProperties properties;

  public PrepReminderScanner(
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      ProvisionQueryService provisionQueryService,
      HouseholdQueryService householdQueryService,
      PlanQueryService planQueryService,
      PrepReminderDispatchLogRepository dispatchLogRepository,
      ScannerProperties properties) {
    super(clock, eventPublisher);
    this.provisionQueryService = provisionQueryService;
    this.householdQueryService = householdQueryService;
    this.planQueryService = planQueryService;
    this.dispatchLogRepository = dispatchLogRepository;
    this.properties = properties;
  }

  /** Scheduled trigger — every 5 minutes by default. The cron is far-future in the test profile. */
  @Scheduled(cron = "${mealprep.notification.scanners.prep-reminder.cron:0 */5 * * * ?}")
  @Transactional
  public int runScheduled() {
    return scan();
  }

  /** Single synchronous scan; returns the number of prep reminders fired this run. */
  @Transactional
  public int scan() {
    Instant now = now();
    ZoneId zone = clock().getZone();
    LocalDate today = LocalDate.ofInstant(now, zone);
    Duration window = Duration.ofMinutes(properties.prepReminder().leadMinutes());
    int fired = 0;
    for (UUID userId : provisionQueryService.getUserIdsWithActiveInventory()) {
      try {
        UUID householdId = householdIdFor(userId);
        if (householdId == null) {
          continue;
        }
        // Look one day ahead so an evening slot's morning prep is caught.
        for (UpcomingSlotView slot :
            planQueryService.getUpcomingSlots(householdId, today, today.plusDays(1))) {
          if (slot.recipeId() == null) {
            continue;
          }
          Instant prepAt = prepMomentFor(slot, zone);
          if (Duration.between(now, prepAt).abs().compareTo(window) > 0) {
            continue;
          }
          if (dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(slot.slotId(), prepAt)) {
            continue;
          }
          UUID traceId = UUID.randomUUID();
          publish(
              new com.example.mealprep.planner.event.PrepReminderEvent(
                  userId,
                  slot.slotId(),
                  slot.recipeId(),
                  "Start advance prep",
                  prepAt,
                  traceId,
                  now));
          dispatchLogRepository.save(
              PrepReminderDispatchLog.builder()
                  .id(UUID.randomUUID())
                  .slotId(slot.slotId())
                  .prepStepAtTime(prepAt)
                  .userId(userId)
                  .firedAt(now)
                  .build());
          fired++;
        }
      } catch (RuntimeException e) {
        log.error("prep-reminder scan failed for userId={}", userId, e);
      }
    }
    log.info("prep-reminder scan complete: fired={}", fired);
    return fired;
  }

  /**
   * Resolve the prep moment. Prefer the slot's explicit {@code prepStepAtTime} override when set;
   * otherwise derive it as the slot's resolved meal time ({@link UpcomingSlotView#mealTime()},
   * never null) minus its time budget (the prep lead). Both are evaluated on {@code dayDate} in the
   * clock's zone, so the recomputed value is stable across scan runs (idempotency holds).
   */
  private static Instant prepMomentFor(UpcomingSlotView slot, ZoneId zone) {
    if (slot.prepStepAtTime() != null) {
      return slot.dayDate().atTime(slot.prepStepAtTime()).atZone(zone).toInstant();
    }
    Instant mealInstant = slot.dayDate().atTime(slot.mealTime()).atZone(zone).toInstant();
    return mealInstant.minus(Duration.ofMinutes(slot.timeBudgetMin()));
  }

  private UUID householdIdFor(UUID userId) {
    return householdQueryService.getByUserId(userId).map(HouseholdDto::id).orElse(null);
  }
}
