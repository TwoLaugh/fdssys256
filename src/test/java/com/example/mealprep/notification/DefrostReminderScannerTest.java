package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.scanner.DefrostReminderScanner;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.entity.DefrostReminderDispatchLog;
import com.example.mealprep.notification.scanner.internal.repository.DefrostReminderDispatchLogRepository;
import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
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
 * Unit tests for {@link DefrostReminderScanner}. The meal anchor is the item's {@code expiryDate}
 * at start-of-day (UTC here); the defrost target = anchor − leadHours. The 1-hour fire window
 * boundary is the focus.
 *
 * <p>Anchor for an item expiring 2026-06-16 is {@code 2026-06-16T00:00Z}; with {@code
 * defrostLeadTimeHours=10} the target is {@code 2026-06-15T14:00Z}.
 */
@ExtendWith(MockitoExtension.class)
class DefrostReminderScannerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 16);

  @Mock private ProvisionQueryService provisionQueryService;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private DefrostReminderDispatchLogRepository dispatchLogRepository;
  @Mock private ApplicationEventPublisher publisher;

  private DefrostReminderScanner scannerAt(String instant) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new DefrostReminderScanner(
        clock,
        publisher,
        provisionQueryService,
        householdQueryService,
        dispatchLogRepository,
        defaultProps());
  }

  @Test
  void withinOneHourOfTarget_fires() {
    // now == target (14:00) → fires.
    var scanner = scannerAt("2026-06-15T14:00:00Z");
    UUID itemId = UUID.randomUUID();
    setUser();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    when(dispatchLogRepository.existsBySlotIdAndDefrostTargetTime(any(), any())).thenReturn(false);
    stubCandidates(frozen(itemId, EXPIRY, 10));

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    DefrostReminderEvent event = capture();
    assertThat(event.inventoryItemId()).isEqualTo(itemId);
    assertThat(event.defrostBy()).isEqualTo(Instant.parse("2026-06-15T14:00:00Z"));
    verify(dispatchLogRepository).save(any(DefrostReminderDispatchLog.class));
  }

  @Test
  void atExactlyOneHourBefore_fires() {
    // now 13:00, target 14:00 → exactly 1h away → within the inclusive window → fires.
    var scanner = scannerAt("2026-06-15T13:00:00Z");
    setUser();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    when(dispatchLogRepository.existsBySlotIdAndDefrostTargetTime(any(), any())).thenReturn(false);
    stubCandidates(frozen(UUID.randomUUID(), EXPIRY, 10));

    assertThat(scanner.scan()).isEqualTo(1);
  }

  @Test
  void moreThanOneHourBefore_doesNotFire() {
    // now 12:30, target 14:00 → 1h30m away → outside window → no fire.
    var scanner = scannerAt("2026-06-15T12:30:00Z");
    setUser();
    stubCandidates(frozen(UUID.randomUUID(), EXPIRY, 10));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void idempotent_existingDispatchRow_isNoOp() {
    var scanner = scannerAt("2026-06-15T14:00:00Z");
    setUser();
    when(dispatchLogRepository.existsBySlotIdAndDefrostTargetTime(any(), any())).thenReturn(true);
    stubCandidates(frozen(UUID.randomUUID(), EXPIRY, 10));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void noCandidates_runsCleanly() {
    var scanner = scannerAt("2026-06-15T14:00:00Z");
    setUser();
    when(provisionQueryService.getDefrostCandidates(USER)).thenReturn(List.of());

    assertThat(scanner.scan()).isZero();
  }

  @Test
  void failureForOneUser_isIsolated() {
    var scanner = scannerAt("2026-06-15T14:00:00Z");
    UUID bad = UUID.randomUUID();
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(bad, USER));
    when(provisionQueryService.getDefrostCandidates(bad)).thenThrow(new RuntimeException("boom"));
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    when(dispatchLogRepository.existsBySlotIdAndDefrostTargetTime(any(), any())).thenReturn(false);
    when(provisionQueryService.getDefrostCandidates(USER))
        .thenReturn(List.of(frozen(UUID.randomUUID(), EXPIRY, 10)));

    assertThat(scanner.scan()).isEqualTo(1);
    verify(publisher, times(1)).publishEvent(any(DefrostReminderEvent.class));
  }

  // ---------------- helpers ----------------

  private void setUser() {
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(USER));
  }

  private void stubCandidates(InventoryItemDto... items) {
    when(provisionQueryService.getDefrostCandidates(USER)).thenReturn(List.of(items));
  }

  private DefrostReminderEvent capture() {
    ArgumentCaptor<DefrostReminderEvent> captor =
        ArgumentCaptor.forClass(DefrostReminderEvent.class);
    verify(publisher).publishEvent(captor.capture());
    return captor.getValue();
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

  private static InventoryItemDto frozen(UUID id, LocalDate expiry, int leadHours) {
    FreezerExtensionDto fx =
        new FreezerExtensionDto(expiry.minusDays(7), 12, null, leadHours, null);
    return new InventoryItemDto(
        id,
        USER,
        "frozen meal",
        "category",
        StorageLocation.FREEZER,
        TrackingMode.QUANTITY,
        BigDecimal.ONE,
        "portion",
        BigDecimal.ZERO,
        StapleStatus.STOCKED,
        false,
        expiry,
        "mapping-key",
        null,
        ItemSource.BATCH_COOK,
        null,
        ItemLifecycleStatus.ACTIVE,
        fx,
        Instant.now(),
        Instant.now(),
        0L);
  }
}
