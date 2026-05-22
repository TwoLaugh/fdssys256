package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.domain.repository.StapleReplenishmentDispatchLogRepository;
import com.example.mealprep.notification.event.StapleReplenishmentNeededEvent;
import com.example.mealprep.notification.scanner.StapleReplenishmentScanner;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.entity.StapleReplenishmentDispatchLog;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link StapleReplenishmentScanner}. Clock fixed on a Sunday at 10:00 UTC; one
 * batch event per user, the {@code lowestStockRatio} OUT/LOW signalling, and idempotency.
 */
@ExtendWith(MockitoExtension.class)
class StapleReplenishmentScannerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate SUNDAY = LocalDate.of(2026, 6, 14); // a Sunday

  @Mock private ProvisionQueryService provisionQueryService;
  @Mock private StapleReplenishmentDispatchLogRepository dispatchLogRepository;
  @Mock private ApplicationEventPublisher publisher;

  private StapleReplenishmentScanner scanner;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);
    scanner =
        new StapleReplenishmentScanner(
            clock, publisher, provisionQueryService, dispatchLogRepository, defaultProps());
  }

  @Test
  void staplesBelowThreshold_fireOneBatchEvent() {
    setUser();
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, SUNDAY)).thenReturn(false);
    UUID low = UUID.randomUUID();
    UUID out = UUID.randomUUID();
    when(provisionQueryService.getStaplesNeedingReplenishment(USER))
        .thenReturn(
            List.of(
                staple(low, StapleStatus.LOW, "paprika"), staple(out, StapleStatus.OUT, "salt")));

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    StapleReplenishmentNeededEvent event = capture();
    assertThat(event.userId()).isEqualTo(USER);
    assertThat(event.inventoryItemIds()).containsExactly(low, out);
    assertThat(event.ingredientMappingKeys()).containsExactly("paprika", "salt");
    // any OUT item -> ratio 0.
    assertThat(event.lowestStockRatio()).isEqualByComparingTo("0");
    verify(dispatchLogRepository).save(any(StapleReplenishmentDispatchLog.class));
  }

  @Test
  void onlyLowItems_ratioIsHalf() {
    setUser();
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, SUNDAY)).thenReturn(false);
    when(provisionQueryService.getStaplesNeedingReplenishment(USER))
        .thenReturn(List.of(staple(UUID.randomUUID(), StapleStatus.LOW, "cumin")));

    assertThat(scanner.scan()).isEqualTo(1);
    assertThat(capture().lowestStockRatio()).isEqualByComparingTo("0.5");
  }

  @Test
  void noStaplesNeedingReplenishment_noEvent() {
    setUser();
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, SUNDAY)).thenReturn(false);
    when(provisionQueryService.getStaplesNeedingReplenishment(USER)).thenReturn(List.of());

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void idempotent_secondRunSameDay_isNoOp() {
    setUser();
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, SUNDAY)).thenReturn(true);

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
    verify(provisionQueryService, never()).getStaplesNeedingReplenishment(any());
  }

  @Test
  void failureForOneUser_isIsolated() {
    UUID bad = UUID.randomUUID();
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(bad, USER));
    when(dispatchLogRepository.existsByUserIdAndScanDate(bad, SUNDAY))
        .thenThrow(new RuntimeException("boom"));
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, SUNDAY)).thenReturn(false);
    when(provisionQueryService.getStaplesNeedingReplenishment(USER))
        .thenReturn(List.of(staple(UUID.randomUUID(), StapleStatus.OUT, "rice")));

    assertThat(scanner.scan()).isEqualTo(1);
    verify(publisher, times(1)).publishEvent(any(StapleReplenishmentNeededEvent.class));
  }

  // ---------------- helpers ----------------

  private void setUser() {
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(USER));
  }

  private StapleReplenishmentNeededEvent capture() {
    ArgumentCaptor<StapleReplenishmentNeededEvent> captor =
        ArgumentCaptor.forClass(StapleReplenishmentNeededEvent.class);
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

  private static InventoryItemDto staple(UUID id, StapleStatus status, String mappingKey) {
    return new InventoryItemDto(
        id,
        USER,
        "staple",
        "category",
        StorageLocation.SPICE_RACK,
        TrackingMode.STATUS,
        null,
        null,
        null,
        status,
        true,
        null,
        mappingKey,
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        Instant.now(),
        Instant.now(),
        0L);
  }
}
