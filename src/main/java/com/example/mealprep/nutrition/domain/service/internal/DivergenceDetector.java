package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.NutritionDivergenceState;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionDivergenceStateRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Detects whether the user's actual intake on a given day has drifted from the planned intake by
 * more than the configured threshold for any macro, and publishes {@link
 * NutritionIntakeDivergedEvent} accordingly. Package-private; invoked by direct in-method call from
 * the four intake-write flows on {@code NutritionServiceImpl} (CONFIRM, OVERRIDE, EDIT, SKIP — NOT
 * snack ops per LLD line 917).
 *
 * <p>Dedup state survives JVM restart via {@code nutrition_divergence_state}. The detector
 * publishes via {@link ApplicationEventPublisher}; Spring delivers the event {@code AFTER_COMMIT}
 * to any registered {@code @TransactionalEventListener(phase = AFTER_COMMIT)} consumer.
 *
 * <p>Called inside the existing parent {@code @Transactional} method so the dedup-state upsert and
 * the parent intake write commit atomically.
 */
@Component
public class DivergenceDetector {

  private static final Logger log = LoggerFactory.getLogger(DivergenceDetector.class);

  // Macros tracked for divergence. Calories is included as a "macro" key per LLD line 917.
  private static final List<String> MACRO_KEYS =
      List.of("calories", "protein", "carbs", "fat", "fibre");

  private final IntakeDayRepository intakeDayRepository;
  private final NutritionTargetsRepository nutritionTargetsRepository;
  private final NutritionDivergenceStateRepository stateRepository;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final BigDecimal threshold;
  private final int minPlannedFloorKcal;

  public DivergenceDetector(
      IntakeDayRepository intakeDayRepository,
      NutritionTargetsRepository nutritionTargetsRepository,
      NutritionDivergenceStateRepository stateRepository,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${mealprep.nutrition.divergence.macro-variance-threshold:0.15}") BigDecimal threshold,
      @Value("${mealprep.nutrition.divergence.minimum-planned-floor-kcal:200}")
          int minPlannedFloorKcal) {
    this.intakeDayRepository = intakeDayRepository;
    this.nutritionTargetsRepository = nutritionTargetsRepository;
    this.stateRepository = stateRepository;
    this.events = events;
    this.clock = clock;
    this.threshold = threshold;
    this.minPlannedFloorKcal = minPlannedFloorKcal;
  }

  /**
   * Run the divergence check for {@code (userId, onDate)} and publish a {@link
   * NutritionIntakeDivergedEvent} via {@link ApplicationEventPublisher} if state has changed since
   * the last publication. Must be called inside an active transaction.
   */
  public void detectAndPublish(UUID userId, LocalDate onDate, UUID traceId) {
    Optional<IntakeDay> dayOpt = intakeDayRepository.findByUserIdAndOnDate(userId, onDate);
    if (dayOpt.isEmpty()) {
      return;
    }
    Optional<NutritionTargets> targetsOpt = nutritionTargetsRepository.findByUserId(userId);
    if (targetsOpt.isEmpty()) {
      return;
    }

    IntakeDay day = dayOpt.get();
    // Force lazy load.
    day.getSlots().size();
    day.getSnacks().size();

    int plannedKcal = sumPlannedKcal(day);
    if (plannedKcal < minPlannedFloorKcal) {
      return;
    }

    boolean hasPending =
        day.getSlots().stream().anyMatch(s -> s.getActualStatus() == IntakeSlotStatus.PENDING);
    Snapshot snapshot = computeSnapshot(day);
    Set<String> newDiverged = snapshot.diverged(threshold);

    NutritionDivergenceState row =
        stateRepository
            .findByUserIdAndOnDate(userId, onDate)
            .orElseGet(() -> NutritionDivergenceState.empty(userId, onDate));
    Set<String> previousDiverged = row.divergedMacrosAsSet();

    boolean freshOrChanged =
        !newDiverged.isEmpty() && !newDiverged.equals(previousDiverged) && hasPending;
    boolean resolution = newDiverged.isEmpty() && !previousDiverged.isEmpty();

    if (freshOrChanged || resolution) {
      events.publishEvent(
          new NutritionIntakeDivergedEvent(
              userId,
              onDate,
              Set.copyOf(newDiverged),
              snapshot.toDto(),
              traceId,
              Instant.now(clock)));
      log.info(
          "nutrition divergence event published userId={} onDate={} diverged={} resolution={}",
          userId,
          onDate,
          newDiverged,
          resolution);
    }

    // Always upsert the row so the sweep can age out idle rows.
    row.setDivergedMacros(new ArrayList<>(newDiverged));
    row.setUpdatedAt(Instant.now(clock));
    stateRepository.save(row);
  }

  // ---------------- helpers ----------------

  private static int sumPlannedKcal(IntakeDay day) {
    int sum = 0;
    for (IntakeSlot s : day.getSlots()) {
      sum += s.getPlannedCalories() == null ? 0 : s.getPlannedCalories();
    }
    return sum;
  }

  /**
   * Compute the planned-so-far and actual-so-far per macro across all decided slots (status NOT
   * PENDING) plus all snacks. Snacks count toward actuals only; their planned counterpart is zero.
   */
  private static Snapshot computeSnapshot(IntakeDay day) {
    Map<String, BigDecimal> planned = new LinkedHashMap<>();
    Map<String, BigDecimal> actual = new LinkedHashMap<>();
    for (String key : MACRO_KEYS) {
      planned.put(key, BigDecimal.ZERO);
      actual.put(key, BigDecimal.ZERO);
    }

    for (IntakeSlot s : day.getSlots()) {
      if (s.getActualStatus() == IntakeSlotStatus.PENDING) {
        continue;
      }
      addMacro(planned, "calories", s.getPlannedCalories());
      addMacro(actual, "calories", s.getActualCalories());
      addMacro(planned, "protein", s.getPlannedProteinG());
      addMacro(actual, "protein", s.getActualProteinG());
      addMacro(planned, "carbs", s.getPlannedCarbsG());
      addMacro(actual, "carbs", s.getActualCarbsG());
      addMacro(planned, "fat", s.getPlannedFatG());
      addMacro(actual, "fat", s.getActualFatG());
      addMacro(planned, "fibre", s.getPlannedFibreG());
      addMacro(actual, "fibre", s.getActualFibreG());
    }
    for (IntakeSnack snack : day.getSnacks()) {
      // Snacks have no planned counterpart.
      addMacro(actual, "calories", snack.getCalories());
      addMacro(actual, "protein", snack.getProteinG());
      addMacro(actual, "carbs", snack.getCarbsG());
      addMacro(actual, "fat", snack.getFatG());
      addMacro(actual, "fibre", snack.getFibreG());
    }
    return new Snapshot(planned, actual);
  }

  private static void addMacro(Map<String, BigDecimal> acc, String key, Integer val) {
    if (val == null) {
      return;
    }
    acc.merge(key, BigDecimal.valueOf(val), BigDecimal::add);
  }

  private static void addMacro(Map<String, BigDecimal> acc, String key, BigDecimal val) {
    if (val == null) {
      return;
    }
    acc.merge(key, val, BigDecimal::add);
  }

  /** Internal snapshot of planned/actual maps + computed percent variance. */
  private record Snapshot(Map<String, BigDecimal> planned, Map<String, BigDecimal> actual) {

    /** Compute {@code (actual - planned) / planned} per macro; undefined when {@code planned=0}. */
    Map<String, BigDecimal> percentVariance() {
      Map<String, BigDecimal> out = new LinkedHashMap<>();
      for (Map.Entry<String, BigDecimal> e : planned.entrySet()) {
        BigDecimal p = e.getValue();
        BigDecimal a = actual.getOrDefault(e.getKey(), BigDecimal.ZERO);
        if (p.signum() == 0) {
          continue;
        }
        out.put(e.getKey(), a.subtract(p).divide(p, 6, RoundingMode.HALF_UP));
      }
      return out;
    }

    /** Macros whose {@code |variance| >= threshold}. */
    Set<String> diverged(BigDecimal threshold) {
      Set<String> out = new LinkedHashSet<>();
      Map<String, BigDecimal> variance = percentVariance();
      for (Map.Entry<String, BigDecimal> e : variance.entrySet()) {
        if (e.getValue().abs().compareTo(threshold) >= 0) {
          out.add(e.getKey());
        }
      }
      return out;
    }

    DivergenceSummaryDto toDto() {
      return new DivergenceSummaryDto(
          copyAtScale2(planned), copyAtScale2(actual), copyAtScale4(percentVariance()));
    }

    private static Map<String, BigDecimal> copyAtScale2(Map<String, BigDecimal> in) {
      Map<String, BigDecimal> out = new LinkedHashMap<>();
      for (Map.Entry<String, BigDecimal> e : in.entrySet()) {
        out.put(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP));
      }
      return out;
    }

    private static Map<String, BigDecimal> copyAtScale4(Map<String, BigDecimal> in) {
      Map<String, BigDecimal> out = new LinkedHashMap<>();
      for (Map.Entry<String, BigDecimal> e : in.entrySet()) {
        out.put(e.getKey(), e.getValue().setScale(4, RoundingMode.HALF_UP));
      }
      return out;
    }
  }
}
