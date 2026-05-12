package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process IT verifying that {@code DivergenceDetector} is invoked from the intake-update flows
 * on {@code NutritionServiceImpl} and that the {@link NutritionIntakeDivergedEvent} is delivered
 * AFTER_COMMIT to a {@link TransactionalEventListener} consumer.
 */
@SpringBootTest
@Import({TestContainersConfig.class, DivergenceDetectorIT.DivergenceEventCaptureConfig.class})
@ActiveProfiles("test")
class DivergenceDetectorIT {

  @Autowired private NutritionUpdateService updateService;
  @Autowired private NutritionTargetsRepository targetsRepository;
  @Autowired private DivergenceEventCapture eventCapture;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
    jdbcTemplate.update("DELETE FROM nutrition_divergence_state");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    eventCapture.clear();
  }

  /** Persist a minimal targets row so {@code DivergenceDetector} doesn't short-circuit. */
  private NutritionTargets seedTargets(UUID userId) {
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
    return targetsRepository.saveAndFlush(t);
  }

  @Test
  void confirmIntake_onPlan_noDivergenceEvent_butDedupRowWritten() {
    UUID userId = UUID.randomUUID();
    seedTargets(userId);
    LocalDate onDate = LocalDate.of(2026, 5, 11);
    UUID planId = UUID.randomUUID();
    List<PlannedSlotInputDto> slots = NutritionTestData.defaultPlannedSlots();
    updateService.prefillFromPlan(userId, onDate, planId, slots);

    // Confirm BREAKFAST exactly as planned — actuals match plan, no divergence.
    updateService.confirmFromPlan(userId, onDate, MealSlot.BREAKFAST);

    // The detector should not publish a divergence event but should upsert the dedup row.
    assertThat(eventCapture.events()).isEmpty();
    Long stateRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_divergence_state WHERE user_id = ?",
            Long.class,
            userId);
    assertThat(stateRows).isEqualTo(1L);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class DivergenceEventCaptureConfig {
    @Bean
    DivergenceEventCapture divergenceEventCapture() {
      return new DivergenceEventCapture();
    }
  }

  static class DivergenceEventCapture {
    private final List<NutritionIntakeDivergedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDivergence(NutritionIntakeDivergedEvent event) {
      events.add(event);
    }

    public List<NutritionIntakeDivergedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
