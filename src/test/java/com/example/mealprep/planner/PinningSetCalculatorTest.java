package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for the package-private {@code PinningSetCalculator} (planner-01i invariant #5).
 * Exercised via reflection (the class is package-private to {@code domain.service.internal.reopt});
 * the four DoD pinning scenarios are each asserted independently: cooked/eaten/skipped, lock-hours
 * window, explicit pin, provisions-trigger affected slot.
 *
 * <p>Time fixtures are anchored to a fixed {@link Clock} (no wall-clock time-bomb): the lock-window
 * boundary is computed relative to the injected instant, not {@code Instant.now()}.
 */
class PinningSetCalculatorTest {

  private static final LocalDate SLOT_DATE = LocalDate.of(2026, 6, 15);
  private static final long SLOT_EPOCH_DAY = SLOT_DATE.toEpochDay();

  /** Reflectively build a calculator with a fixed clock. */
  private static Object newCalc(Instant fixedNow) throws Exception {
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt.PinningSetCalculator");
    Constructor<?> ctor = cls.getDeclaredConstructor(Clock.class);
    ctor.setAccessible(true);
    return ctor.newInstance(Clock.fixed(fixedNow, ZoneOffset.UTC));
  }

  private static PinnedReason pinReason(
      Object calc, MealSlot slot, int lockHours, ReoptTriggerKind trigger, Set<UUID> affected)
      throws Exception {
    Method m =
        calc.getClass()
            .getDeclaredMethod(
                "pinReason",
                MealSlot.class,
                long.class,
                int.class,
                ReoptTriggerKind.class,
                Set.class);
    m.setAccessible(true);
    return (PinnedReason) m.invoke(calc, slot, SLOT_EPOCH_DAY, lockHours, trigger, affected);
  }

  private static MealSlot slot(SlotState state, PinnedReason explicitPin) {
    Day day = Day.builder().id(UUID.randomUUID()).onDate(SLOT_DATE).build();
    return MealSlot.builder()
        .id(UUID.randomUUID())
        .day(day)
        .slotIndex(0)
        .kind(SlotKind.DINNER)
        .label("Dinner")
        .timeBudgetMin(30)
        .shared(true)
        .state(state)
        .pinnedReason(explicitPin)
        .build();
  }

  /** Far enough before the slot date that the lock window is NOT open. */
  private static Instant wellBefore() {
    return SLOT_DATE.minusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  @Test
  void cooked_eaten_skipped_cooking_are_each_pinned_with_matching_reason() throws Exception {
    Object calc = newCalc(wellBefore());
    assertThat(pinReason(calc, slot(SlotState.COOKED, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isEqualTo(PinnedReason.COOKED);
    assertThat(pinReason(calc, slot(SlotState.EATEN, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isEqualTo(PinnedReason.EATEN);
    assertThat(pinReason(calc, slot(SlotState.SKIPPED, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isEqualTo(PinnedReason.SKIPPED);
    assertThat(pinReason(calc, slot(SlotState.COOKING, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isEqualTo(PinnedReason.COOKING);
  }

  @Test
  void planned_slot_well_outside_lock_window_is_regenerable() throws Exception {
    Object calc = newCalc(wellBefore());
    assertThat(pinReason(calc, slot(SlotState.PLANNED, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isNull();
  }

  @Test
  void planned_slot_inside_lock_window_is_pinned_lock_window() throws Exception {
    // now = slot midnight - 23h => inside the 24h lock window (boundary-sensitive: kills the
    // off-by-one / wrong-comparator mutants).
    Instant slotMidnight = SLOT_DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
    Object calcInside = newCalc(slotMidnight.minusSeconds(23 * 3600));
    assertThat(
            pinReason(
                calcInside, slot(SlotState.PLANNED, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isEqualTo(PinnedReason.LOCK_WINDOW);

    // now = slot midnight - 25h => OUTSIDE the 24h lock window => regenerable.
    Object calcOutside = newCalc(slotMidnight.minusSeconds(25 * 3600));
    assertThat(
            pinReason(
                calcOutside, slot(SlotState.PLANNED, null), 24, ReoptTriggerKind.USER, Set.of()))
        .isNull();
  }

  @Test
  void explicit_pin_takes_precedence_for_a_planned_slot() throws Exception {
    Object calc = newCalc(wellBefore());
    assertThat(
            pinReason(
                calc,
                slot(SlotState.PLANNED, PinnedReason.USER_PINNED),
                24,
                ReoptTriggerKind.USER,
                Set.of()))
        .isEqualTo(PinnedReason.USER_PINNED);
  }

  @Test
  void provisions_trigger_affected_slot_is_pinned_trigger_affected() throws Exception {
    Object calc = newCalc(wellBefore());
    MealSlot s = slot(SlotState.PLANNED, null);
    assertThat(pinReason(calc, s, 24, ReoptTriggerKind.PROVISIONS, Set.of(s.getId())))
        .isEqualTo(PinnedReason.TRIGGER_AFFECTED);
  }

  @Test
  void affected_slot_under_non_provisions_trigger_is_NOT_trigger_pinned() throws Exception {
    Object calc = newCalc(wellBefore());
    MealSlot s = slot(SlotState.PLANNED, null);
    // NUTRITION trigger + slot in the affected set, but well outside lock window => regenerable.
    assertThat(pinReason(calc, s, 24, ReoptTriggerKind.NUTRITION, Set.of(s.getId()))).isNull();
  }
}
