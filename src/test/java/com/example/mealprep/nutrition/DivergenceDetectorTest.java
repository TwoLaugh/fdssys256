package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionDivergenceState;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionDivergenceStateRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Unit-level coverage of {@link DivergenceDetector}. */
@ExtendWith(MockitoExtension.class)
class DivergenceDetectorTest {

  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionDivergenceStateRepository stateRepository;
  @Mock private ApplicationEventPublisher events;

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC);
  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate ON_DATE = LocalDate.of(2026, 5, 11);
  private static final UUID TRACE = UUID.randomUUID();

  private DivergenceDetector detector() {
    return new DivergenceDetector(
        intakeDayRepository,
        targetsRepository,
        stateRepository,
        events,
        clock,
        new BigDecimal("0.15"),
        200);
  }

  @Test
  void noIntakeDay_silentNoOp() {
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
  }

  @Test
  void noTargets_silentNoOp() {
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(
            Optional.of(day(slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30))));
    when(targetsRepository.findByUserId(USER)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
  }

  @Test
  void belowMinimumPlannedKcal_silentNoOp() {
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(
            Optional.of(day(slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 100, 10))));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
  }

  @Test
  void varianceCrossesThreshold_andPendingExists_publishesEvent() {
    // Planned 30g protein, actual 36g (variance = +20%, above 15%). Calories also drift +20%.
    IntakeSlot decided = slotWithActuals(MealSlot.BREAKFAST, 500, 30, 600, 36);
    IntakeSlot pending = pendingSlot(MealSlot.LUNCH, 600, 40);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> captor =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(captor.capture());
    NutritionIntakeDivergedEvent ev = captor.getValue();
    assertThat(ev.userId()).isEqualTo(USER);
    assertThat(ev.onDate()).isEqualTo(ON_DATE);
    assertThat(ev.divergedMacros()).contains("protein");
    verify(stateRepository, times(1)).save(any());
  }

  @Test
  void identicalSubsequentDivergence_dedupSuppresses() {
    IntakeSlot decided = slotWithActuals(MealSlot.BREAKFAST, 500, 30, 600, 36);
    IntakeSlot pending = pendingSlot(MealSlot.LUNCH, 600, 40);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    NutritionDivergenceState row =
        NutritionDivergenceState.builder()
            .userId(USER)
            .onDate(ON_DATE)
            .divergedMacros(new ArrayList<>(List.of("protein", "calories")))
            .updatedAt(Instant.EPOCH)
            .build();
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.of(row));

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
    // Row still updated so the sweep can age it out.
    verify(stateRepository, times(1)).save(any());
  }

  @Test
  void previouslyDivergedNowEmpty_publishesResolutionEvent() {
    // Day with everything on-plan now; previously protein was diverged.
    IntakeSlot onPlan = slotWithActuals(MealSlot.BREAKFAST, 500, 30, 500, 30);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(onPlan)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    NutritionDivergenceState row =
        NutritionDivergenceState.builder()
            .userId(USER)
            .onDate(ON_DATE)
            .divergedMacros(new ArrayList<>(List.of("protein")))
            .updatedAt(Instant.EPOCH)
            .build();
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.of(row));

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> captor =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(captor.capture());
    assertThat(captor.getValue().divergedMacros()).isEqualTo(Set.of());
  }

  @Test
  void allDecidedNoPending_freshDivergenceNotEmitted_butResolutionIs() {
    // No pending slot — even with variance > threshold, no new divergence.
    IntakeSlot off = slotWithActuals(MealSlot.BREAKFAST, 500, 30, 600, 36);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(off)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
  }

  @Test
  void varianceBelowThreshold_noEvent_stateUpsertedEmpty() {
    // 5% variance is under 15%. New diverged set is empty; previous also empty -> no event.
    IntakeSlot decided = slotWithActuals(MealSlot.BREAKFAST, 500, 30, 525, 31);
    IntakeSlot pending = pendingSlot(MealSlot.LUNCH, 600, 40);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
    verify(stateRepository, times(1)).save(any());
  }

  // ---------------- fixtures ----------------

  private static IntakeDay day(IntakeSlot... slots) {
    IntakeDay d =
        IntakeDay.builder()
            .id(UUID.randomUUID())
            .userId(USER)
            .onDate(ON_DATE)
            .slots(new ArrayList<>())
            .snacks(new ArrayList<>())
            .auditLog(new ArrayList<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    for (IntakeSlot s : slots) {
      d.addSlot(s);
    }
    return d;
  }

  /** Slot with status set; actuals=0 unless overridden later. */
  private static IntakeSlot slot(
      MealSlot meal, IntakeSlotStatus status, int plannedKcal, int plannedProtein) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(meal)
        .plannedCalories(plannedKcal)
        .plannedProteinG(BigDecimal.valueOf(plannedProtein))
        .plannedCarbsG(BigDecimal.valueOf(50))
        .plannedFatG(BigDecimal.valueOf(20))
        .plannedFibreG(BigDecimal.valueOf(8))
        .actualStatus(status)
        .actualCalories(0)
        .actualProteinG(BigDecimal.ZERO)
        .actualCarbsG(BigDecimal.ZERO)
        .actualFatG(BigDecimal.ZERO)
        .actualFibreG(BigDecimal.ZERO)
        .build();
  }

  private static IntakeSlot pendingSlot(MealSlot meal, int plannedKcal, int plannedProtein) {
    return slot(meal, IntakeSlotStatus.PENDING, plannedKcal, plannedProtein);
  }

  /** A decided slot whose actual values diverge from plan. */
  private static IntakeSlot slotWithActuals(
      MealSlot meal, int plannedKcal, int plannedProtein, int actualKcal, int actualProtein) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(meal)
        .plannedCalories(plannedKcal)
        .plannedProteinG(BigDecimal.valueOf(plannedProtein))
        .plannedCarbsG(BigDecimal.valueOf(50))
        .plannedFatG(BigDecimal.valueOf(20))
        .plannedFibreG(BigDecimal.valueOf(8))
        .actualStatus(IntakeSlotStatus.CONFIRMED)
        .actualCalories(actualKcal)
        .actualProteinG(BigDecimal.valueOf(actualProtein))
        .actualCarbsG(BigDecimal.valueOf(50))
        .actualFatG(BigDecimal.valueOf(20))
        .actualFibreG(BigDecimal.valueOf(8))
        .build();
  }
}
