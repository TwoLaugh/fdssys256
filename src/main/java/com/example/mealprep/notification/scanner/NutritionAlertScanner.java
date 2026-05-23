package com.example.mealprep.notification.scanner;

import com.example.mealprep.notification.domain.repository.NutritionAlertDispatchLogRepository;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.ScannerSupport;
import com.example.mealprep.notification.scanner.internal.entity.NutritionAlertDispatchLog;
import com.example.mealprep.nutrition.api.dto.ActualIntakeDto;
import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeSlotDto;
import com.example.mealprep.nutrition.api.dto.IntakeSnackDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily scanner (21:00 by default, after most evening meals) that compares each user's
 * intake-so-far against their daily nutrition target and alerts when a macro has meaningfully
 * diverged. Per {@code tickets/notification/01b-scanners.md} §Scanner #4.
 *
 * <p>For each user with configured targets it totals today's actual intake (slot actuals + snacks)
 * and, for {@code calories / protein / carbs / fat}, computes {@code divergencePct = |actual −
 * target| / target}. When that is {@code >= threshold} (default 0.30) and no alert has yet fired
 * for {@code (userId, date, nutrient)}, it fires one {@code NutritionIntakeDivergedEvent} per
 * diverged nutrient. The scanner does <strong>not</strong> classify severity — the notification
 * listener tiers INFO/ATTENTION off the divergence (per the LLD's resolver, {@code >= 0.40 →
 * ATTENTION}).
 *
 * <p>Idempotent per {@code (userId, alertDate, nutrientKey)} via {@link
 * NutritionAlertDispatchLogRepository}.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.notification.scanners",
    name = "nutrition-alert.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NutritionAlertScanner extends ScannerSupport {

  private final NutritionQueryService nutritionQueryService;
  private final NutritionAlertDispatchLogRepository dispatchLogRepository;
  private final ScannerProperties properties;

  public NutritionAlertScanner(
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      NutritionQueryService nutritionQueryService,
      NutritionAlertDispatchLogRepository dispatchLogRepository,
      ScannerProperties properties) {
    super(clock, eventPublisher);
    this.nutritionQueryService = nutritionQueryService;
    this.dispatchLogRepository = dispatchLogRepository;
    this.properties = properties;
  }

  /** Scheduled trigger — daily 21:00 by default. The cron is far-future in the test profile. */
  @Scheduled(cron = "${mealprep.notification.scanners.nutrition-alert.cron:0 0 21 * * ?}")
  @Transactional
  public int runScheduled() {
    return scan();
  }

  /** Single synchronous scan; returns the number of nutrition alerts fired this run. */
  @Transactional
  public int scan() {
    Instant now = now();
    ZoneId zone = clock().getZone();
    LocalDate today = LocalDate.ofInstant(now, zone);
    BigDecimal threshold = properties.nutritionAlert().threshold();
    int fired = 0;
    for (UUID userId : nutritionQueryService.getUserIdsWithTargets()) {
      try {
        Optional<TargetsDto> targetsOpt = nutritionQueryService.getTargets(userId);
        Optional<IntakeDayDto> dayOpt = nutritionQueryService.getIntakeForDay(userId, today);
        if (targetsOpt.isEmpty() || dayOpt.isEmpty()) {
          continue;
        }
        Map<String, BigDecimal> targets = targetsOf(targetsOpt.get());
        Map<String, BigDecimal> actuals = actualsOf(dayOpt.get());
        for (Map.Entry<String, BigDecimal> e : targets.entrySet()) {
          String nutrient = e.getKey();
          BigDecimal target = e.getValue();
          if (target == null || target.signum() <= 0) {
            continue;
          }
          BigDecimal actual = actuals.getOrDefault(nutrient, BigDecimal.ZERO);
          BigDecimal divergence =
              actual.subtract(target).abs().divide(target, 4, RoundingMode.HALF_UP);
          if (divergence.compareTo(threshold) < 0) {
            continue;
          }
          if (dispatchLogRepository.existsByUserIdAndAlertDateAndNutrientKey(
              userId, today, nutrient)) {
            continue;
          }
          publish(buildEvent(userId, today, nutrient, target, actual, now));
          dispatchLogRepository.save(
              NutritionAlertDispatchLog.builder()
                  .id(UUID.randomUUID())
                  .userId(userId)
                  .alertDate(today)
                  .nutrientKey(nutrient)
                  .firedAt(now)
                  .build());
          fired++;
        }
      } catch (RuntimeException ex) {
        log.error("nutrition-alert scan failed for userId={}", userId, ex);
      }
    }
    log.info("nutrition-alert scan complete: fired={} alertDate={}", fired, today);
    return fired;
  }

  private static NutritionIntakeDivergedEvent buildEvent(
      UUID userId,
      LocalDate date,
      String nutrient,
      BigDecimal target,
      BigDecimal actual,
      Instant now) {
    // Signed variance (actual - target) / target — fractional units, matching DivergenceDetector.
    BigDecimal variance = actual.subtract(target).divide(target, 4, RoundingMode.HALF_UP);
    DivergenceSummaryDto summary =
        new DivergenceSummaryDto(
            Map.of(nutrient, target), Map.of(nutrient, actual), Map.of(nutrient, variance));
    return new NutritionIntakeDivergedEvent(
        userId, date, Set.of(nutrient), summary, UUID.randomUUID(), now);
  }

  private static Map<String, BigDecimal> targetsOf(TargetsDto targets) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (targets.calories() != null) {
      out.put("calories", BigDecimal.valueOf(targets.calories().dailyTarget()));
    }
    putMacro(out, "protein", targets.protein() == null ? null : targets.protein().targetG());
    putMacro(out, "carbs", targets.carbs() == null ? null : targets.carbs().targetG());
    putMacro(out, "fat", targets.fat() == null ? null : targets.fat().targetG());
    return out;
  }

  private static void putMacro(Map<String, BigDecimal> acc, String key, BigDecimal value) {
    if (value != null) {
      acc.put(key, value);
    }
  }

  private static Map<String, BigDecimal> actualsOf(IntakeDayDto day) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    out.put("calories", BigDecimal.ZERO);
    out.put("protein", BigDecimal.ZERO);
    out.put("carbs", BigDecimal.ZERO);
    out.put("fat", BigDecimal.ZERO);
    if (day.slots() != null) {
      for (IntakeSlotDto slot : day.slots()) {
        ActualIntakeDto a = slot.actual();
        if (a == null) {
          continue;
        }
        addInt(out, "calories", a.calories());
        addDec(out, "protein", a.proteinG());
        addDec(out, "carbs", a.carbsG());
        addDec(out, "fat", a.fatG());
      }
    }
    if (day.snacks() != null) {
      for (IntakeSnackDto snack : day.snacks()) {
        addInt(out, "calories", snack.calories());
        addDec(out, "protein", snack.proteinG());
        addDec(out, "carbs", snack.carbsG());
        addDec(out, "fat", snack.fatG());
      }
    }
    return out;
  }

  private static void addInt(Map<String, BigDecimal> acc, String key, Integer value) {
    if (value != null) {
      acc.merge(key, BigDecimal.valueOf(value), BigDecimal::add);
    }
  }

  private static void addDec(Map<String, BigDecimal> acc, String key, BigDecimal value) {
    if (value != null) {
      acc.merge(key, value, BigDecimal::add);
    }
  }
}
