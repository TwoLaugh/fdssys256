package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.domain.repository.PrepReminderDispatchLogRepository;
import com.example.mealprep.notification.scanner.PrepReminderScanner;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.entity.PrepReminderDispatchLog;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.event.PrepReminderEvent;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link PrepReminderScanner}. The prep moment is derived as {@code dayDate@mealTime
 * − timeBudgetMin} from the slot's resolved wall-clock meal time (planner-01m's {@code
 * UpcomingSlotView.mealTime()}), or the explicit {@code prepStepAtTime} override when set. For a
 * DINNER slot whose resolved meal time is 19:00 with {@code timeBudgetMin=45} the prep moment is
 * {@code 18:15}; the 15-minute fire window is the focus.
 */
@ExtendWith(MockitoExtension.class)
class PrepReminderScannerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID HOUSEHOLD = UUID.randomUUID();
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

  @Mock private ProvisionQueryService provisionQueryService;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private PlanQueryService planQueryService;
  @Mock private PrepReminderDispatchLogRepository dispatchLogRepository;
  @Mock private ApplicationEventPublisher publisher;

  private PrepReminderScanner scannerAt(String instant) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new PrepReminderScanner(
        clock,
        publisher,
        provisionQueryService,
        householdQueryService,
        planQueryService,
        dispatchLogRepository,
        defaultProps());
  }

  @Test
  void within15MinutesOfPrepMoment_fires() {
    // prep moment = 18:00 − 15min = 17:45; now == 17:45 → fires.
    var scanner = scannerAt("2026-06-15T17:45:00Z");
    setUserHousehold();
    UUID slotId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    stubSlots(slot(slotId, recipeId, SlotKind.DINNER, 15));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(false);

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    PrepReminderEvent event = capture();
    assertThat(event.plannedMealSlotId()).isEqualTo(slotId);
    assertThat(event.recipeId()).isEqualTo(recipeId);
    assertThat(event.prepBy()).isEqualTo(Instant.parse("2026-06-15T17:45:00Z"));
    verify(dispatchLogRepository).save(any(PrepReminderDispatchLog.class));
  }

  @Test
  void realConfiguredMealTime_firesRelativeToResolvedMealTimeMinusBudget() {
    // Owner lifestyle config resolves DINNER to 19:00; budget 45 → prep moment = 18:15.
    // now == 18:15 → fires. (Old hard-coded default would have been 18:00 − 45 = 17:15.)
    var scanner = scannerAt("2026-06-15T18:15:00Z");
    setUserHousehold();
    UUID slotId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    stubSlots(slot(slotId, recipeId, SlotKind.DINNER, 45, LocalTime.of(19, 0), null));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(false);

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    assertThat(capture().prepBy()).isEqualTo(Instant.parse("2026-06-15T18:15:00Z"));
  }

  @Test
  void realConfiguredMealTime_outsideWindow_doesNotFire() {
    // Resolved DINNER 19:00, budget 45 → prep 18:15; now 17:55 → 20min away → no fire.
    // (The old 18:00-default would have fired here at 17:15 ± 15min; the real meal time does not.)
    var scanner = scannerAt("2026-06-15T17:55:00Z");
    setUserHousehold();
    stubSlots(
        slot(UUID.randomUUID(), UUID.randomUUID(), SlotKind.DINNER, 45, LocalTime.of(19, 0), null));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void explicitPrepStepAtTimeOverride_firesRelativeToOverrideNotDerivedMealTime() {
    // prepStepAtTime override = 16:30 takes precedence over (mealTime 19:00 − budget 45 = 18:15).
    var scanner = scannerAt("2026-06-15T16:30:00Z");
    setUserHousehold();
    UUID slotId = UUID.randomUUID();
    stubSlots(
        slot(
            slotId,
            UUID.randomUUID(),
            SlotKind.DINNER,
            45,
            LocalTime.of(19, 0),
            LocalTime.of(16, 30)));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(false);

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    assertThat(capture().prepBy()).isEqualTo(Instant.parse("2026-06-15T16:30:00Z"));
  }

  @Test
  void atExactly15MinutesBefore_fires() {
    // now 17:30, prep moment 17:45 → exactly 15min away → inclusive lead window → fires.
    var scanner = scannerAt("2026-06-15T17:30:00Z");
    setUserHousehold();
    stubSlots(slot(UUID.randomUUID(), UUID.randomUUID(), SlotKind.DINNER, 15));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(false);

    assertThat(scanner.scan()).isEqualTo(1);
  }

  @Test
  void moreThan15MinutesBefore_doesNotFire() {
    // now 17:29, prep moment 17:45 → 16min away → no fire.
    var scanner = scannerAt("2026-06-15T17:29:00Z");
    setUserHousehold();
    stubSlots(slot(UUID.randomUUID(), UUID.randomUUID(), SlotKind.DINNER, 15));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void emptySlot_noRecipe_isSkipped() {
    var scanner = scannerAt("2026-06-15T17:45:00Z");
    setUserHousehold();
    stubSlots(slot(UUID.randomUUID(), null, SlotKind.DINNER, 15));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void idempotent_existingRow_isNoOp() {
    var scanner = scannerAt("2026-06-15T17:45:00Z");
    setUserHousehold();
    stubSlots(slot(UUID.randomUUID(), UUID.randomUUID(), SlotKind.DINNER, 15));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(true);

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void userWithNoHousehold_isSkipped() {
    var scanner = scannerAt("2026-06-15T17:45:00Z");
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(USER));
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());

    assertThat(scanner.scan()).isZero();
    verify(planQueryService, never()).getUpcomingSlots(any(), any(), any());
  }

  @Test
  void failureForOneUser_isIsolated() {
    var scanner = scannerAt("2026-06-15T17:45:00Z");
    UUID bad = UUID.randomUUID();
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(bad, USER));
    when(householdQueryService.getByUserId(bad)).thenThrow(new RuntimeException("boom"));
    when(householdQueryService.getByUserId(USER))
        .thenReturn(
            Optional.of(new HouseholdDto(HOUSEHOLD, "h", USER, List.of(), Instant.now(), 0)));
    stubSlots(slot(UUID.randomUUID(), UUID.randomUUID(), SlotKind.DINNER, 15));
    when(dispatchLogRepository.existsBySlotIdAndPrepStepAtTime(any(), any())).thenReturn(false);

    assertThat(scanner.scan()).isEqualTo(1);
    verify(publisher, times(1)).publishEvent(any(PrepReminderEvent.class));
  }

  // ---------------- helpers ----------------

  private void setUserHousehold() {
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(USER));
    when(householdQueryService.getByUserId(USER))
        .thenReturn(
            Optional.of(new HouseholdDto(HOUSEHOLD, "h", USER, List.of(), Instant.now(), 0)));
  }

  private void stubSlots(UpcomingSlotView... slots) {
    when(planQueryService.getUpcomingSlots(
            eq(HOUSEHOLD), any(LocalDate.class), any(LocalDate.class)))
        .thenReturn(List.of(slots));
  }

  private PrepReminderEvent capture() {
    ArgumentCaptor<PrepReminderEvent> captor = ArgumentCaptor.forClass(PrepReminderEvent.class);
    verify(publisher).publishEvent(captor.capture());
    return captor.getValue();
  }

  /**
   * Slot with the slot-kind default meal time the planner would resolve when a household has no
   * lifestyle config (DINNER 18:00) — preserves the pre-01c no-config arithmetic (prep = 18:00 −
   * budget).
   */
  private static UpcomingSlotView slot(UUID slotId, UUID recipeId, SlotKind kind, int timeBudget) {
    return slot(slotId, recipeId, kind, timeBudget, LocalTime.of(18, 0), null);
  }

  private static UpcomingSlotView slot(
      UUID slotId,
      UUID recipeId,
      SlotKind kind,
      int timeBudget,
      LocalTime mealTime,
      LocalTime prepStepAtTime) {
    return new UpcomingSlotView(
        slotId,
        UUID.randomUUID(),
        HOUSEHOLD,
        TODAY,
        kind,
        0,
        timeBudget,
        recipeId,
        mealTime,
        prepStepAtTime);
  }

  private static ScannerProperties defaultProps() {
    return new ScannerProperties(
        new ScannerProperties.ExpiryWarning(true, null, 2, 14, 7),
        new ScannerProperties.DefrostReminder(true, null),
        new ScannerProperties.PrepReminder(true, null, 15),
        new ScannerProperties.NutritionAlert(true, null, new BigDecimal("0.30")),
        new ScannerProperties.StapleReplenishment(true, null),
        30);
  }
}
