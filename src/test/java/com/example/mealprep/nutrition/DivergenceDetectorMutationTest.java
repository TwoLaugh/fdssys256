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
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.IntakeSource;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Mutation-killing coverage for {@link DivergenceDetector}. The existing {@code
 * DivergenceDetectorTest} used slots whose actuals equalled plan except protein, leaving these
 * mutants alive:
 *
 * <ul>
 *   <li>L103 ConditionalsBoundary — {@code plannedKcal < minPlannedFloorKcal}; boundary equality
 *       (plannedKcal == 200) was never exercised.
 *   <li>L108 NegateConditionals / BooleanTrueReturnVals — the {@code hasPending} predicate lambda.
 *   <li>L140/L141 VoidMethodCall — {@code row.setDivergedMacros(...)} / {@code row.setUpdatedAt}.
 *   <li>L168 NegateConditionals — PENDING slots skipped in {@code computeSnapshot}.
 *   <li>L171-179 VoidMethodCall — per-macro {@code addMacro(planned/actual,...)} calls.
 *   <li>L184-188 (NO_COVERAGE) — snack {@code addMacro(actual,...)} calls.
 *   <li>L194 NegateConditionals — {@code addMacro(Integer)} null guard.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DivergenceDetectorMutationTest {

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
  void plannedKcalExactlyAtFloor_isNotBelow_soDetectionProceeds() {
    // L103: `plannedKcal < 200`. With plannedKcal == 200 the guard is false, so detection must
    // proceed. ConditionalsBoundary (< -> <=) would make it return early -> no save.
    // One decided slot with 150 planned + one pending with 50 planned == 200 total planned.
    IntakeSlot decided = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 150, 30, 180, 40);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 50, 10, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    // Guard not triggered -> the always-upsert save runs.
    verify(stateRepository, times(1)).save(any());
  }

  @Test
  void plannedKcalBelowFloor_returnsEarly_noSave() {
    IntakeSlot decided = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 199, 30, 250, 45);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(stateRepository, never()).save(any());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void savedRow_carriesNewlyDivergedMacros_andClockUpdatedAt() {
    // L140 setDivergedMacros + L141 setUpdatedAt: assert the persisted row content.
    IntakeSlot decided = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30, 600, 36);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionDivergenceState> rowCap =
        ArgumentCaptor.forClass(NutritionDivergenceState.class);
    verify(stateRepository).save(rowCap.capture());
    NutritionDivergenceState saved = rowCap.getValue();
    // protein +20% and calories +20% both diverge -> row must record them (kills L140 removal,
    // which would leave the row's macros at the empty default).
    assertThat(saved.getDivergedMacros()).contains("protein", "calories");
    // updatedAt set from the fixed clock (kills L141 removal which would leave EPOCH default).
    assertThat(saved.getUpdatedAt()).isEqualTo(Instant.parse("2026-05-11T10:00:00Z"));
  }

  @Test
  void pendingSlotActualsExcludedFromSnapshot() {
    // L168 NegateConditionals: PENDING slots must be skipped in computeSnapshot. Decided slot is
    // on-plan; pending slot has wildly off actuals. If PENDING were INCLUDED the pending slot's
    // huge actuals would cause divergence + an event. Correct behaviour: no fresh divergence.
    IntakeSlot decided = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 600, 40, 600, 40);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 5000, 999);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    // Decided slot is exactly on-plan; pending excluded -> no divergence -> no event.
    verify(events, never()).publishEvent(any());
  }

  @Test
  void onlyProteinActualDiverges_carbsRemainOnPlan_eventCarriesProteinNotCarbs() {
    // L171-179 VoidMethodCall: each macro is summed via its own addMacro pair. Build a slot where
    // protein actual diverges (+25%) but carbs/fat/fibre/calories actual == planned. If the carbs
    // addMacro(actual,...) call were removed, carbs would show planned>0 actual=0 => -100% =>
    // carbs would (wrongly) appear in divergedMacros. Asserting carbs is ABSENT kills those.
    IntakeSlot decided =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.BREAKFAST)
            .plannedCalories(600)
            .plannedProteinG(BigDecimal.valueOf(40))
            .plannedCarbsG(BigDecimal.valueOf(80))
            .plannedFatG(BigDecimal.valueOf(20))
            .plannedFibreG(BigDecimal.valueOf(10))
            .actualStatus(IntakeSlotStatus.CONFIRMED)
            .actualCalories(600) // on plan
            .actualProteinG(BigDecimal.valueOf(50)) // +25% -> diverged
            .actualCarbsG(BigDecimal.valueOf(80)) // on plan
            .actualFatG(BigDecimal.valueOf(20)) // on plan
            .actualFibreG(BigDecimal.valueOf(10)) // on plan
            .build();
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> cap =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(cap.capture());
    assertThat(cap.getValue().divergedMacros()).contains("protein");
    assertThat(cap.getValue().divergedMacros()).doesNotContain("carbs", "fat", "fibre", "calories");
  }

  @Test
  void snackPushesCaloriesOverThreshold_snackActualsAreCounted() {
    // L184-188 (snack addMacro): a decided slot exactly on-plan plus a big snack. Snacks count
    // toward actuals only. The snack alone must push EVERY macro's variance over 15%. If any of
    // the snack addMacro(actual, "calories"/"protein"/"carbs"/"fat"/"fibre") calls were removed,
    // that macro would NOT diverge — so asserting all five present kills L184/185/186/187/188.
    IntakeSlot decided = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 800, 50, 800, 50);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    IntakeDay d = day(decided, pending);
    // decided slot baseline: calories 800, protein 50, carbs 50, fat 20, fibre 8. The snack adds
    // calories 400, protein 30, carbs 40, fat 12, fibre 5 -> every macro > +15%.
    d.addSnack(richSnack(400, 30, 40, 12, 5));
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.of(d));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> cap =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(cap.capture());
    assertThat(cap.getValue().divergedMacros())
        .contains("calories", "protein", "carbs", "fat", "fibre");
  }

  @Test
  void everyMacroDivergesIndependently_slotPlannedAndActualBothCounted() {
    // Kills L175/L177/L179 (and re-confirms L171/L173) VoidMethodCall on the slot
    // addMacro(planned/actual, "carbs"/"fat"/"fibre", ...) pairs. Each macro's actual is +25%
    // over its plan. Removing addMacro(planned,"carbs",...) makes planned carbs 0 -> variance is
    // undefined (signum()==0 -> skipped) -> carbs would NOT appear; removing the actual side
    // makes actual==0 -> -100% but actual side is the same as protein which is covered. Asserting
    // each of carbs/fat/fibre IS present pins both calls per macro.
    IntakeSlot decided =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.BREAKFAST)
            .plannedCalories(600)
            .plannedProteinG(BigDecimal.valueOf(40))
            .plannedCarbsG(BigDecimal.valueOf(80))
            .plannedFatG(BigDecimal.valueOf(20))
            .plannedFibreG(BigDecimal.valueOf(10))
            .actualStatus(IntakeSlotStatus.CONFIRMED)
            .actualCalories(750) // +25%
            .actualProteinG(BigDecimal.valueOf(50)) // +25%
            .actualCarbsG(BigDecimal.valueOf(100)) // +25%
            .actualFatG(BigDecimal.valueOf(25)) // +25%
            .actualFibreG(BigDecimal.valueOf(13)) // +30%
            .build();
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(decided, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> cap =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(cap.capture());
    assertThat(cap.getValue().divergedMacros())
        .containsExactlyInAnyOrder("calories", "protein", "carbs", "fat", "fibre");
  }

  @Test
  void hasPending_false_suppressesFreshEvent_noEventInteraction() {
    // L108 lambda `s -> s.getActualStatus() == PENDING`. With NO pending slot the predicate must
    // evaluate false for every slot -> hasPending=false -> freshOrChanged=false -> NO event.
    // BooleanTrueReturnVals (lambda always true) or NegateConditionals (== -> !=, which is true
    // for the CONFIRMED slot) would make hasPending=true -> freshOrChanged=true -> an event WOULD
    // publish. The decided slot diverges hard on calories(+40%) and protein(+60%) so newDiverged
    // is non-empty and the ONLY thing gating the event is hasPending. verifyNoInteractions on the
    // events publisher is matcher-free and fails the instant a single publishEvent happens.
    IntakeSlot diverged = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30, 700, 48);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(diverged)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    // Original: hasPending=false -> no event at all -> events mock untouched.
    // Mutated (always-true / negated lambda): hasPending=true -> publishEvent invoked -> this
    // verifyNoInteractions fails -> mutant killed.
    org.mockito.Mockito.verifyNoInteractions(events);
    // Row is still upserted regardless (sanity that we executed the full method, not an early
    // return) — proves the test genuinely reached the freshOrChanged decision at L118-122.
    verify(stateRepository, times(1)).save(any());
  }

  @Test
  void hasPending_true_allowsFreshEvent_exactlyOnePublication() {
    // Companion to the above with identical decided slot but a PENDING slot added. Now the
    // predicate returns true for the pending slot -> hasPending=true -> event publishes exactly
    // once. The two tests together pin the lambda's boolean meaning.
    IntakeSlot diverged = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30, 700, 48);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(diverged, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> capPending =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(capPending.capture());
    assertThat(capPending.getValue().divergedMacros()).contains("protein", "calories");
  }

  @Test
  void nullPlannedCalories_treatedAsZero_notSkippingActual() {
    // L194 NegateConditionals on addMacro(Integer val): `if (val == null) return;`. A decided slot
    // with null plannedCalories but a real actualCalories. plannedKcal floor uses sumPlannedKcal
    // (separate), so set another slot to clear the 200 floor. The null-planned-calories slot must
    // contribute its actual to the snapshot; planned calories stays 0 so calories variance is
    // huge -> calories diverges. NegateConditionals (treat null as non-null) would NPE/behave
    // differently.
    IntakeSlot floorSlot = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 300, 20, 300, 20);
    IntakeSlot nullPlanned =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.LUNCH)
            .plannedCalories(null)
            .plannedProteinG(BigDecimal.valueOf(30))
            .plannedCarbsG(BigDecimal.valueOf(40))
            .plannedFatG(BigDecimal.valueOf(15))
            .plannedFibreG(BigDecimal.valueOf(8))
            .actualStatus(IntakeSlotStatus.CONFIRMED)
            .actualCalories(700)
            .actualProteinG(BigDecimal.valueOf(30))
            .actualCarbsG(BigDecimal.valueOf(40))
            .actualFatG(BigDecimal.valueOf(15))
            .actualFibreG(BigDecimal.valueOf(8))
            .build();
    IntakeSlot pending = slot(MealSlot.DINNER, IntakeSlotStatus.PENDING, 200, 10, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(floorSlot, nullPlanned, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> cap =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(cap.capture());
    // planned calories = 300 (floorSlot only; nullPlanned contributes 0), actual = 1000
    // -> +233% variance -> calories diverged. Protein on-plan -> not diverged.
    assertThat(cap.getValue().divergedMacros()).contains("calories");
  }

  @Test
  void noPendingSlot_freshDivergenceSuppressed_butStateStillUpserted() {
    // L108: hasPending predicate. All slots decided & diverged but NO pending slot. freshOrChanged
    // requires hasPending, so no event; but the row is still upserted. NegateConditionals /
    // BooleanTrue on the lambda would flip this into emitting an event.
    IntakeSlot off = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30, 650, 45);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(off)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    detector().detectAndPublish(USER, ON_DATE, TRACE);

    verify(events, never()).publishEvent(any());
    verify(stateRepository, times(1)).save(any());
  }

  @Test
  void withPendingSlot_freshDivergenceEmitted() {
    // Companion to noPendingSlot_*: identical decided slot but WITH a pending slot -> event fires.
    // This pair pins the hasPending lambda value (true vs false changes the outcome). Build the
    // detector ONCE and reuse it so the verify targets the same collaborator interaction.
    IntakeSlot off = slot(MealSlot.BREAKFAST, IntakeSlotStatus.CONFIRMED, 500, 30, 650, 45);
    IntakeSlot pending = slot(MealSlot.LUNCH, IntakeSlotStatus.PENDING, 600, 40, 0, 0);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, ON_DATE))
        .thenReturn(Optional.of(day(off, pending)));
    when(targetsRepository.findByUserId(USER))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(USER).build()));
    when(stateRepository.findByUserIdAndOnDate(USER, ON_DATE)).thenReturn(Optional.empty());

    DivergenceDetector d = detector();
    d.detectAndPublish(USER, ON_DATE, TRACE);

    ArgumentCaptor<NutritionIntakeDivergedEvent> cap =
        ArgumentCaptor.forClass(NutritionIntakeDivergedEvent.class);
    verify(events, times(1)).publishEvent(cap.capture());
    assertThat(cap.getValue().divergedMacros()).contains("protein", "calories");
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

  private static IntakeSlot slot(
      MealSlot meal,
      IntakeSlotStatus status,
      int plannedKcal,
      int plannedProtein,
      int actualKcal,
      int actualProtein) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(meal)
        .plannedCalories(plannedKcal)
        .plannedProteinG(BigDecimal.valueOf(plannedProtein))
        .plannedCarbsG(BigDecimal.valueOf(50))
        .plannedFatG(BigDecimal.valueOf(20))
        .plannedFibreG(BigDecimal.valueOf(8))
        .actualStatus(status)
        .actualCalories(actualKcal)
        .actualProteinG(BigDecimal.valueOf(actualProtein))
        .actualCarbsG(BigDecimal.valueOf(50))
        .actualFatG(BigDecimal.valueOf(20))
        .actualFibreG(BigDecimal.valueOf(8))
        .build();
  }

  private static IntakeSnack snack(int kcal, int protein) {
    return richSnack(kcal, protein, 20, 10, 3);
  }

  private static IntakeSnack richSnack(int kcal, int protein, int carbs, int fat, int fibre) {
    return IntakeSnack.builder()
        .id(UUID.randomUUID())
        .freeText("protein bar")
        .quantityG(BigDecimal.valueOf(60))
        .calories(kcal)
        .proteinG(BigDecimal.valueOf(protein))
        .carbsG(BigDecimal.valueOf(carbs))
        .fatG(BigDecimal.valueOf(fat))
        .fibreG(BigDecimal.valueOf(fibre))
        .source(IntakeSource.MANUAL)
        .loggedAt(Instant.now())
        .build();
  }
}
