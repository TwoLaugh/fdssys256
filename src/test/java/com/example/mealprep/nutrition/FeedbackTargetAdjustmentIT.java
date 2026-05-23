package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.origin.ActorType;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Service-layer Testcontainers IT for {@code NutritionUpdateService.applyFeedbackAdjustment}
 * (nutrition-01i). Seeds a real {@code nutrition_targets} row (with a micro + per-meal child),
 * applies single-field adjustments against real Postgres, and asserts: the value nudged, ONE {@code
 * nutrition_targets_audit} row written with {@code actor_kind=FEEDBACK} / {@code actor_type=AI} /
 * {@code origin_trace} / null {@code source_directive_id}, the {@code @Version} bumped, and the
 * AFTER_COMMIT {@link NutritionTargetsChangedEvent} fired carrying the single changed field.
 */
@SpringBootTest
@Import({TestContainersConfig.class, FeedbackTargetAdjustmentIT.EventCaptureConfig.class})
@ActiveProfiles("test")
class FeedbackTargetAdjustmentIT {

  @Autowired private NutritionUpdateService updateService;
  @Autowired private NutritionTargetsRepository targetsRepository;
  @Autowired private NutritionTargetsAuditRepository auditRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TargetsChangedCapture capture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    capture.clear();
  }

  @Test
  void applyFeedbackAdjustment_macroIncrease_nudgesPersistsAuditBumpsVersionFiresEvent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets seeded = seedTargets(userId);
    long versionBefore = seeded.getVersion();

    TargetsDto result =
        updateService.applyFeedbackAdjustment(
            userId,
            adjustment(
                "protein_target_g",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.MODERATE,
                null,
                "feedback-" + UUID.randomUUID()));

    assertThat(result.protein().targetG()).isEqualByComparingTo("132.0"); // 120 + 10%
    assertThat(result.version()).isGreaterThan(versionBefore);

    NutritionTargets reloaded = targetsRepository.findByUserId(userId).orElseThrow();
    assertThat(reloaded.getProteinTargetG()).isEqualByComparingTo("132.0");
    // Enforcement untouched.
    assertThat(reloaded.getProteinDirection()).isEqualTo(EnforcementDirection.LOWER_FLOOR);

    List<NutritionTargetsAuditLog> audit = auditRepository.findAll();
    assertThat(audit).hasSize(1);
    NutritionTargetsAuditLog row = audit.get(0);
    assertThat(row.getFieldPath()).isEqualTo("protein_target_g");
    assertThat(row.getActorKind()).isEqualTo(ActorKind.FEEDBACK);
    assertThat(row.getActorType()).isEqualTo(ActorType.AI);
    assertThat(row.getActorUserId()).isEqualTo(userId);
    assertThat(row.getSourceDirectiveId()).isNull();
    assertThat(row.getOriginTrace()).startsWith("feedback-");

    assertThat(capture.events()).hasSize(1);
    assertThat(capture.events().get(0).changedFieldPaths()).containsExactly("protein_target_g");
  }

  @Test
  void applyFeedbackAdjustment_microDecrease_nudgesExistingRow() {
    UUID userId = UUID.randomUUID();
    seedTargets(userId);

    TargetsDto result =
        updateService.applyFeedbackAdjustment(
            userId,
            adjustment(
                "micro.sodium_mg",
                AdjustmentDirection.DECREASE,
                AdjustmentMagnitude.MODERATE,
                null,
                "feedback-" + UUID.randomUUID()));

    assertThat(result.microTargets())
        .anySatisfy(
            m -> {
              assertThat(m.nutrientKey()).isEqualTo("sodium_mg");
              assertThat(m.targetValue()).isEqualByComparingTo("2070.0"); // 2300 - 10%
            });
    assertThat(auditRepository.findAll().get(0).getFieldPath()).isEqualTo("micro.sodium_mg.target");
  }

  @Test
  void applyFeedbackAdjustment_microNotOptedIn_isNoOp_noAuditNoEvent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets seeded = seedTargets(userId);
    long versionBefore = seeded.getVersion();

    updateService.applyFeedbackAdjustment(
        userId,
        adjustment(
            "micro.iron_mg",
            AdjustmentDirection.DECREASE,
            AdjustmentMagnitude.MODERATE,
            null,
            "feedback-" + UUID.randomUUID()));

    assertThat(auditRepository.findAll()).isEmpty();
    assertThat(capture.events()).isEmpty();
    assertThat(targetsRepository.findByUserId(userId).orElseThrow().getVersion())
        .isEqualTo(versionBefore);
  }

  @Test
  void applyFeedbackAdjustment_absoluteValue_setsExactly() {
    UUID userId = UUID.randomUUID();
    seedTargets(userId);

    TargetsDto result =
        updateService.applyFeedbackAdjustment(
            userId,
            adjustment(
                "calorie_target",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.LARGE,
                new BigDecimal("2200"),
                "feedback-" + UUID.randomUUID()));

    assertThat(result.calories().dailyTarget()).isEqualTo(2200);
  }

  @Test
  void applyFeedbackAdjustment_crossTenant_doesNotTouchAnotherUsersTargets() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    seedTargets(userA);
    NutritionTargets bSeed = seedTargets(userB);

    updateService.applyFeedbackAdjustment(
        userA,
        adjustment(
            "protein_target_g",
            AdjustmentDirection.INCREASE,
            AdjustmentMagnitude.LARGE,
            null,
            "feedback-" + UUID.randomUUID()));

    assertThat(targetsRepository.findByUserId(userB).orElseThrow().getProteinTargetG())
        .isEqualByComparingTo(bSeed.getProteinTargetG());
  }

  // ---------------- helpers ----------------

  private FeedbackTargetAdjustment adjustment(
      String target,
      AdjustmentDirection direction,
      AdjustmentMagnitude magnitude,
      BigDecimal absoluteValue,
      String reasonTrace) {
    return new FeedbackTargetAdjustment(target, direction, magnitude, absoluteValue, reasonTrace);
  }

  @Transactional
  NutritionTargets seedTargets(UUID userId) {
    NutritionTargets t =
        NutritionTargets.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .goal(Goal.MAINTAIN)
            .dailyCalorieTarget(2000)
            .calorieToleranceUnder(100)
            .calorieToleranceOver(150)
            .calorieEnforcement("weekly_average")
            .calorieDirection(EnforcementDirection.BOTH_BOUNDED)
            .proteinTargetG(BigDecimal.valueOf(120.0))
            .proteinFloorG(null)
            .proteinEnforcement("daily_floor")
            .proteinDirection(EnforcementDirection.LOWER_FLOOR)
            .carbsTargetG(BigDecimal.valueOf(250.0))
            .carbsFloorG(null)
            .carbsEnforcement("weekly_average")
            .carbsDirection(EnforcementDirection.BOTH_BOUNDED)
            .fatTargetG(BigDecimal.valueOf(70.0))
            .fatFloorG(null)
            .fatEnforcement("weekly_average")
            .fatDirection(EnforcementDirection.BOTH_BOUNDED)
            .fibreTargetG(BigDecimal.valueOf(30.0))
            .fibreFloorG(null)
            .fibreEnforcement("daily_floor")
            .fibreDirection(EnforcementDirection.LOWER_FLOOR)
            .satFatTargetG(BigDecimal.valueOf(20.0))
            .satFatDirection(EnforcementDirection.UPPER_LIMIT)
            .notes(null)
            .userOverriddenDirections(new ArrayList<>())
            .perMealDistribution(new ArrayList<>())
            .microTargets(new ArrayList<>())
            .activityAdjustments(new ArrayList<>())
            .eatingWindow(null)
            .build();
    MicroTarget sodium =
        MicroTarget.builder()
            .id(UUID.randomUUID())
            .nutrientKey("sodium_mg")
            .targetValue(new BigDecimal("2300"))
            .build();
    sodium.setTarget(t);
    t.getMicroTargets().add(sodium);
    PerMealDistributionEntry lunch =
        PerMealDistributionEntry.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.LUNCH)
            .calorieTarget(600)
            .proteinTargetG(new BigDecimal("40.0"))
            .build();
    lunch.setTarget(t);
    t.getPerMealDistribution().add(lunch);
    return targetsRepository.saveAndFlush(t);
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    TargetsChangedCapture targetsChangedCapture() {
      return new TargetsChangedCapture();
    }
  }

  static class TargetsChangedCapture {
    private final List<NutritionTargetsChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(NutritionTargetsChangedEvent event) {
      events.add(event);
    }

    List<NutritionTargetsChangedEvent> events() {
      return events;
    }

    void clear() {
      events.clear();
    }
  }
}
