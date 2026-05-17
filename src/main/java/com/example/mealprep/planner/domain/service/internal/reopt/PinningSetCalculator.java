package com.example.mealprep.planner.domain.service.internal.reopt;

import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.SlotState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pure pinning-rule evaluator for mid-week re-opt (planner-01i invariant #5). Decides, per {@link
 * MealSlot}, whether it must NOT change and (if so) why. No I/O, no DB — a deterministic function
 * of slot state + the slot's date + config + the trigger. Package-private; the coordinator is the
 * only caller.
 *
 * <p>A slot is pinned if ANY of:
 *
 * <ul>
 *   <li>the user has acted on it — {@code state ∈ {COOKING, COOKED, EATEN, SKIPPED}};
 *   <li>it is still {@code PLANNED} but inside its lock-hours window — {@code now >= slotDate 00:00
 *       − lockHoursBeforeSlot} (default 24h, {@code mealprep.planner.mid-week
 *       .lock-hours-before-slot});
 *   <li>it carries an explicit pin — {@code pinnedReason != null} (a {@code USER_PINNED} / prior
 *       re-opt pin);
 *   <li>it is the trigger's affected slot AND the trigger is a provisions trigger — the 01k
 *       listener already replaced it; the re-opt must not second-guess it.
 * </ul>
 *
 * <p>Mapping note: the 01i ticket's invariant #5 references {@code state = PROVISIONED} and {@code
 * ReoptTriggerKind.INGREDIENT_OUT_OF_STOCK}; this codebase's {@link SlotState} has no {@code
 * PROVISIONED} value (the regenerable state is {@code PLANNED}) and {@link ReoptTriggerKind} has no
 * {@code INGREDIENT_OUT_OF_STOCK} value (the closest classified kind is {@link
 * ReoptTriggerKind#PROVISIONS}). The lock-window rule is therefore applied to {@code PLANNED} slots
 * and the trigger-affected rule to {@code PROVISIONS} triggers — the behaviour the ticket
 * specifies, expressed against the real enums.
 */
@Component
class PinningSetCalculator {

  private final Clock clock;

  /** Spring wires the system-zone clock; tests inject a fixed clock to avoid time-bomb fixtures. */
  PinningSetCalculator() {
    this(Clock.systemUTC());
  }

  PinningSetCalculator(Clock clock) {
    this.clock = clock;
  }

  /**
   * Compute the pin verdict for {@code slot}.
   *
   * @param slot the slot under evaluation (its day must be hydrated for the date read)
   * @param slotDateEpochDay the slot's calendar date as epoch-day (caller resolves from the day)
   * @param lockHoursBeforeSlot the lock-window size in hours
   * @param trigger the re-opt trigger kind
   * @param affectedSlotIds the trigger's affected slot ids (never null; empty if none)
   * @return the {@link PinnedReason} if pinned, or {@code null} if the slot is regenerable
   */
  PinnedReason pinReason(
      MealSlot slot,
      long slotDateEpochDay,
      int lockHoursBeforeSlot,
      ReoptTriggerKind trigger,
      Set<UUID> affectedSlotIds) {

    SlotState state = slot.getState();
    if (state == SlotState.EATEN) {
      return PinnedReason.EATEN;
    }
    if (state == SlotState.COOKED) {
      return PinnedReason.COOKED;
    }
    if (state == SlotState.COOKING) {
      return PinnedReason.COOKING;
    }
    if (state == SlotState.SKIPPED) {
      return PinnedReason.SKIPPED;
    }

    // Explicit per-slot pin (user manually pinned, or a prior re-opt pinned it).
    if (slot.getPinnedReason() != null) {
      return slot.getPinnedReason();
    }

    // Trigger's own affected slot under a provisions trigger — listener already replaced it.
    if (trigger == ReoptTriggerKind.PROVISIONS
        && affectedSlotIds != null
        && affectedSlotIds.contains(slot.getId())) {
      return PinnedReason.TRIGGER_AFFECTED;
    }

    // PLANNED but inside the lock-hours window: now >= slotDate-midnight - lockHours.
    Instant slotMidnight =
        Instant.ofEpochSecond(slotDateEpochDay * 86_400L).atZone(ZoneOffset.UTC).toInstant();
    Instant lockOpensAt = slotMidnight.minus(Duration.ofHours(lockHoursBeforeSlot));
    if (!clock.instant().isBefore(lockOpensAt)) {
      return PinnedReason.LOCK_WINDOW;
    }

    return null; // regenerable — part of the search space
  }

  /**
   * Convenience: true if {@code slot} is pinned. Equivalent to {@code pinReason(...) != null}.
   *
   * @see #pinReason(MealSlot, long, int, ReoptTriggerKind, Set)
   */
  boolean isPinned(
      MealSlot slot,
      Map<UUID, Long> slotDateEpochDayBySlotId,
      int lockHoursBeforeSlot,
      ReoptTriggerKind trigger,
      Set<UUID> affectedSlotIds) {
    Long epochDay = slotDateEpochDayBySlotId.get(slot.getId());
    long resolved = epochDay == null ? Long.MAX_VALUE / 86_400L : epochDay;
    return pinReason(slot, resolved, lockHoursBeforeSlot, trigger, affectedSlotIds) != null;
  }
}
