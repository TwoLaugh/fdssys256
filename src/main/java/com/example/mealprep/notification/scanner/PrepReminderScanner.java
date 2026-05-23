package com.example.mealprep.notification.scanner;

import com.example.mealprep.core.types.SlotKind;
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
import java.time.LocalTime;
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
 * <p>The planner does not (yet) store a wall-clock meal time or an explicit {@code
 * prep_step_at_time} — those are an unbuilt "pre-cook actions" planner concern (see {@code
 * design/provision-model.md}). The scanner therefore derives a deterministic prep moment from the
 * slot's real scheduling facts: {@code prepStepAtTime = dayDate@defaultMealTime(kind) −
 * timeBudgetMin}, evaluated in the clock's zone. When the current time is within {@code
 * leadMinutes} (default 15) of that moment, it fires a {@code PrepReminderEvent}.
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

  /** Derive the prep moment: the slot's meal time minus its time budget (the prep lead). */
  private static Instant prepMomentFor(UpcomingSlotView slot, ZoneId zone) {
    LocalTime mealTime = defaultMealTime(slot.kind());
    Instant mealInstant = slot.dayDate().atTime(mealTime).atZone(zone).toInstant();
    return mealInstant.minus(Duration.ofMinutes(slot.timeBudgetMin()));
  }

  /** Default wall-clock meal time per slot kind — the planner has no explicit time field in v1. */
  private static LocalTime defaultMealTime(SlotKind kind) {
    return switch (kind) {
      case BREAKFAST -> LocalTime.of(8, 0);
      case LUNCH -> LocalTime.of(12, 30);
      case DINNER -> LocalTime.of(18, 0);
      case SNACK -> LocalTime.of(15, 0);
      case CUSTOM -> LocalTime.of(12, 0);
    };
  }

  private UUID householdIdFor(UUID userId) {
    return householdQueryService.getByUserId(userId).map(HouseholdDto::id).orElse(null);
  }
}
