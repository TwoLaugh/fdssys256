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
 * Unit tests for {@link PrepReminderScanner}. The prep moment is derived as {@code
 * dayDate@defaultMealTime(kind) − timeBudgetMin}. For a DINNER slot (default 18:00) with {@code
 * timeBudgetMin=15} the prep moment is {@code 17:45}; the 15-minute fire window is the focus.
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

  private static UpcomingSlotView slot(UUID slotId, UUID recipeId, SlotKind kind, int timeBudget) {
    return new UpcomingSlotView(
        slotId, UUID.randomUUID(), HOUSEHOLD, TODAY, kind, 0, timeBudget, recipeId);
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
