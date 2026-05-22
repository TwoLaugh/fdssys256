package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.notification.scanner.NutritionAlertScanner;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.entity.NutritionAlertDispatchLog;
import com.example.mealprep.notification.scanner.internal.repository.NutritionAlertDispatchLogRepository;
import com.example.mealprep.nutrition.api.dto.ActualIntakeDto;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeSlotDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
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
 * Unit tests for {@link NutritionAlertScanner}. Clock fixed at 21:00 UTC; divergence boundary
 * (default threshold 0.30) and idempotency are the focus. The scanner does not classify severity —
 * it emits a signed-variance summary the listener tiers.
 */
@ExtendWith(MockitoExtension.class)
class NutritionAlertScannerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

  @Mock private NutritionQueryService nutritionQueryService;
  @Mock private NutritionAlertDispatchLogRepository dispatchLogRepository;
  @Mock private ApplicationEventPublisher publisher;

  private NutritionAlertScanner scanner;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-15T21:00:00Z"), ZoneOffset.UTC);
    scanner =
        new NutritionAlertScanner(
            clock, publisher, nutritionQueryService, dispatchLogRepository, defaultProps());
  }

  @Test
  void caloriesDivergedExactly30Pct_fires() {
    // target 2000, actual 1400 → divergence 0.30 == threshold → fires.
    setUser();
    stubTargets(2000);
    stubIntakeCalories(1400);
    when(dispatchLogRepository.existsByUserIdAndAlertDateAndNutrientKey(USER, TODAY, "calories"))
        .thenReturn(false);

    int fired = scanner.scan();

    assertThat(fired).isEqualTo(1);
    NutritionIntakeDivergedEvent event = capture();
    assertThat(event.userId()).isEqualTo(USER);
    assertThat(event.onDate()).isEqualTo(TODAY);
    assertThat(event.divergedMacros()).containsExactly("calories");
    // signed variance (1400-2000)/2000 = -0.30.
    assertThat(event.summary().percentVariance().get("calories")).isEqualByComparingTo("-0.30");
    verify(dispatchLogRepository).save(any(NutritionAlertDispatchLog.class));
  }

  @Test
  void caloriesDivergedBelowThreshold_doesNotFire() {
    // target 2000, actual 1700 → divergence 0.15 < 0.30 → no fire.
    setUser();
    stubTargets(2000);
    stubIntakeCalories(1700);

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void severeDivergence_stillEmitsEventForListenerToTier() {
    // target 2000, actual 1100 → divergence 0.45; the scanner fires regardless of severity.
    setUser();
    stubTargets(2000);
    stubIntakeCalories(1100);
    when(dispatchLogRepository.existsByUserIdAndAlertDateAndNutrientKey(USER, TODAY, "calories"))
        .thenReturn(false);

    assertThat(scanner.scan()).isEqualTo(1);
    assertThat(capture().summary().percentVariance().get("calories")).isEqualByComparingTo("-0.45");
  }

  @Test
  void idempotent_existingAlert_isNoOp() {
    setUser();
    stubTargets(2000);
    stubIntakeCalories(1400);
    when(dispatchLogRepository.existsByUserIdAndAlertDateAndNutrientKey(USER, TODAY, "calories"))
        .thenReturn(true);

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void noIntakeDay_isSkipped() {
    setUser();
    when(nutritionQueryService.getTargets(USER)).thenReturn(Optional.of(targets(2000)));
    when(nutritionQueryService.getIntakeForDay(USER, TODAY)).thenReturn(Optional.empty());

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void zeroTarget_isSkipped_noDivideByZero() {
    setUser();
    when(nutritionQueryService.getTargets(USER)).thenReturn(Optional.of(targets(0)));
    when(nutritionQueryService.getIntakeForDay(USER, TODAY)).thenReturn(Optional.of(dayWith(500)));

    assertThat(scanner.scan()).isZero();
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void failureForOneUser_isIsolated() {
    UUID bad = UUID.randomUUID();
    when(nutritionQueryService.getUserIdsWithTargets()).thenReturn(List.of(bad, USER));
    when(nutritionQueryService.getTargets(bad)).thenThrow(new RuntimeException("boom"));
    when(nutritionQueryService.getTargets(USER)).thenReturn(Optional.of(targets(2000)));
    when(nutritionQueryService.getIntakeForDay(USER, TODAY)).thenReturn(Optional.of(dayWith(1400)));
    when(dispatchLogRepository.existsByUserIdAndAlertDateAndNutrientKey(USER, TODAY, "calories"))
        .thenReturn(false);

    assertThat(scanner.scan()).isEqualTo(1);
  }

  // ---------------- helpers ----------------

  private void setUser() {
    when(nutritionQueryService.getUserIdsWithTargets()).thenReturn(List.of(USER));
  }

  private void stubTargets(int kcal) {
    when(nutritionQueryService.getTargets(USER)).thenReturn(Optional.of(targets(kcal)));
  }

  private void stubIntakeCalories(int kcal) {
    when(nutritionQueryService.getIntakeForDay(eq(USER), eq(TODAY)))
        .thenReturn(Optional.of(dayWith(kcal)));
  }

  private NutritionIntakeDivergedEvent capture() {
    ArgumentCaptor<NutritionIntakeDivergedEvent> captor =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
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

  private static TargetsDto targets(int kcal) {
    CalorieTargetDto calories =
        new CalorieTargetDto(kcal, 0, 0, "daily_band", EnforcementDirection.BOTH_BOUNDED);
    return new TargetsDto(
        UUID.randomUUID(),
        USER,
        null, // goal
        calories,
        null, // protein
        null, // carbs
        null, // fat
        null, // fibre
        null, // satFat
        null, // notes
        List.of(), // userOverriddenDirections
        List.of(), // perMealDistribution
        List.of(), // microTargets
        null, // eatingWindow
        List.of(), // activityAdjustments
        Instant.now(),
        0L);
  }

  private static IntakeDayDto dayWith(int actualCalories) {
    ActualIntakeDto actual =
        new ActualIntakeDto(
            IntakeSlotStatus.CONFIRMED,
            actualCalories,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false);
    IntakeSlotDto slot = new IntakeSlotDto(UUID.randomUUID(), MealSlot.DINNER, null, actual);
    return new IntakeDayDto(UUID.randomUUID(), USER, TODAY, null, List.of(slot), List.of(), 0L);
  }
}
