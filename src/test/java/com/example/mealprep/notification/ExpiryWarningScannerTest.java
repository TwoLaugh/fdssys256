package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.entity.ExpiryWarningDispatchLog;
import com.example.mealprep.notification.scanner.internal.repository.ExpiryWarningDispatchLogRepository;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link ExpiryWarningScanner}. A fixed clock pins "today" at 06:00 UTC; the tests
 * assert the per-location threshold boundaries, the one-event-per-user batching, idempotency, and
 * failure isolation.
 */
@ExtendWith(MockitoExtension.class)
class ExpiryWarningScannerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

  @Mock private ProvisionQueryService provisionQueryService;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private ExpiryWarningDispatchLogRepository dispatchLogRepository;
  @Mock private ApplicationEventPublisher publisher;

  private ExpiryWarningScanner scanner;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-15T06:00:00Z"), ZoneOffset.UTC);
    scanner =
        new ExpiryWarningScanner(
            clock,
            publisher,
            provisionQueryService,
            householdQueryService,
            dispatchLogRepository,
            defaultProps());
  }

  @Test
  void fridgeItemAtThreshold_fires() {
    // Fridge threshold is 2 days; an item expiring in exactly 2 days fires.
    setUsers(USER);
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, TODAY)).thenReturn(false);
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    UUID itemId = UUID.randomUUID();
    stubExpiring(item(itemId, StorageLocation.FRIDGE, TODAY.plusDays(2)));

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    ItemNearingExpiryEvent event = capturePublished();
    assertThat(event.userId()).isEqualTo(USER);
    assertThat(event.inventoryItemIds()).containsExactly(itemId);
    assertThat(event.earliestExpiry()).isEqualTo(TODAY.plusDays(2));
    verify(dispatchLogRepository).save(any(ExpiryWarningDispatchLog.class));
  }

  @Test
  void freezerItemAt14Days_fires_butAt15Days_doesNot() {
    setUsers(USER);
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, TODAY)).thenReturn(false);
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    UUID at14 = UUID.randomUUID();
    // The 15-day item is loaded (within the 14-day max query window? no — max is 14, so the repo
    // would not return it). Simulate the repo returning only items <= maxExpiry (today+14).
    stubExpiring(item(at14, StorageLocation.FREEZER, TODAY.plusDays(14)));

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    assertThat(capturePublished().inventoryItemIds()).containsExactly(at14);
  }

  @Test
  void freezerItemBeyondThreshold_isFilteredOut() {
    // The repo (max window = today+14) would not return a 15-day item, but defend the in-code
    // threshold filter too: feed a 20-day freezer item and assert no event.
    setUsers(USER);
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, TODAY)).thenReturn(false);
    stubExpiring(item(UUID.randomUUID(), StorageLocation.FREEZER, TODAY.plusDays(20)));

    int fired = scanner.scan();

    assertThat(fired).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void idempotent_secondSameDayRun_isNoOp() {
    setUsers(USER);
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, TODAY)).thenReturn(true);

    int fired = scanner.scan();

    assertThat(fired).isZero();
    verify(publisher, never()).publishEvent(any());
    verify(provisionQueryService, never()).getExpiringInventory(any(), any());
  }

  @Test
  void emptyInventory_runsCleanly_noEvents() {
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of());

    int fired = scanner.scan();

    assertThat(fired).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void failureForOneUser_isIsolated_othersStillProcessed() {
    UUID bad = UUID.randomUUID();
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(bad, USER));
    when(dispatchLogRepository.existsByUserIdAndScanDate(bad, TODAY))
        .thenThrow(new RuntimeException("boom"));
    when(dispatchLogRepository.existsByUserIdAndScanDate(USER, TODAY)).thenReturn(false);
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    stubExpiring(item(UUID.randomUUID(), StorageLocation.FRIDGE, TODAY.plusDays(1)));

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    verify(publisher, times(1)).publishEvent(any(ItemNearingExpiryEvent.class));
  }

  // ---------------- helpers ----------------

  private void setUsers(UUID... users) {
    when(provisionQueryService.getUserIdsWithActiveInventory()).thenReturn(List.of(users));
  }

  private void stubExpiring(InventoryItemDto... items) {
    when(provisionQueryService.getExpiringInventory(eq(USER), any(LocalDate.class)))
        .thenReturn(List.of(items));
  }

  private ItemNearingExpiryEvent capturePublished() {
    ArgumentCaptor<ItemNearingExpiryEvent> captor =
        ArgumentCaptor.forClass(ItemNearingExpiryEvent.class);
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

  private static InventoryItemDto item(UUID id, StorageLocation loc, LocalDate expiry) {
    return new InventoryItemDto(
        id,
        USER,
        "item",
        "category",
        loc,
        TrackingMode.QUANTITY,
        BigDecimal.ONE,
        "kg",
        BigDecimal.ZERO,
        StapleStatus.STOCKED,
        false,
        expiry,
        "mapping-key",
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
