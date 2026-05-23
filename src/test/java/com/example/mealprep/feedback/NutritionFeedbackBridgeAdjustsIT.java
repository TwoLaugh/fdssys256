package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Testcontainers IT for the now-live NUTRITION feedback bridge (nutrition-01i). Drives the real
 * {@link NutritionFeedbackBridge} Spring bean — the same call the dispatcher makes — against real
 * Postgres with a seeded {@code nutrition_targets} row, and asserts the full AFTER_COMMIT atomicity
 * (decision-log 0010): the target nudge + audit row + {@code DISPATCHED} idempotency row commit
 * together, with the AFTER_COMMIT {@link NutritionTargetsChangedEvent} observed after the call
 * returns (proving the surrounding REQUIRES_NEW-template tx committed). Also covers the
 * unsupported-target FAILED path and idempotency.
 */
@SpringBootTest
@Import({TestContainersConfig.class, NutritionFeedbackBridgeAdjustsIT.ChangeCaptureConfig.class})
@ActiveProfiles("test")
class NutritionFeedbackBridgeAdjustsIT {

  @Autowired private NutritionFeedbackBridge nutritionBridge;
  @Autowired private NutritionTargetsRepository targetsRepository;
  @Autowired private NutritionTargetsAuditRepository auditRepository;
  @Autowired private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChangeCapture changeCapture;

  @AfterEach
  void cleanup() {
    idempotencyRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    changeCapture.clear();
  }

  @Test
  void applyFeedback_decreaseSodiumModerate_adjustsAuditAndBooksDispatchedAtomically() {
    UUID userId = UUID.randomUUID();
    seedTargetsWithSodium(userId);

    UUID feedbackId = UUID.randomUUID();
    NutritionFeedbackBridge.Result result =
        nutritionBridge.applyFeedback(
            adjust(feedbackId, userId, "micro.sodium_mg", "decrease", "moderate"));

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(result.payload()).containsEntry("originTrace", "feedback-" + feedbackId);

    // sodium 2300 -> 2070 (-10%). Asserted via JDBC to avoid lazy-initialising microTargets outside
    // a Hibernate session (the bridge applies the change within its own transaction; reading the
    // detached aggregate's lazy child collection here would throw LazyInitializationException).
    BigDecimal sodium =
        jdbcTemplate.queryForObject(
            "SELECT mt.target_value FROM nutrition_micro_target mt"
                + " JOIN nutrition_targets t ON t.id = mt.targets_id"
                + " WHERE t.user_id = ?::uuid AND mt.nutrient_key = 'sodium_mg'",
            BigDecimal.class,
            userId.toString());
    assertThat(sodium).isEqualByComparingTo("2070.0");

    // One audit row, feedback / AI / origin_trace, committed with the adjustment.
    List<NutritionTargetsAuditLog> audit = auditRepository.findAll();
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).getActorKind()).isEqualTo(ActorKind.FEEDBACK);
    assertThat(audit.get(0).getFieldPath()).isEqualTo("micro.sodium_mg.target");
    assertThat(audit.get(0).getOriginTrace()).isEqualTo("feedback-" + feedbackId);

    // DISPATCHED idempotency row committed in the same unit of work.
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.NUTRITION)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);

    // AFTER_COMMIT event observed (proves the tx committed).
    assertThat(changeCapture.events()).hasSize(1);
    assertThat(changeCapture.events().get(0).changedFieldPaths())
        .containsExactly("micro.sodium_mg.target");
  }

  @Test
  void applyFeedback_unsupportedTarget_booksFailed_andThrows_noTargetChange() {
    UUID userId = UUID.randomUUID();
    NutritionTargets seeded = seedTargetsWithSodium(userId);
    BigDecimal proteinBefore = seeded.getProteinTargetG();

    UUID feedbackId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                nutritionBridge.applyFeedback(
                    adjust(feedbackId, userId, "vibes", "increase", "moderate")))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.NUTRITION)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.FAILED);
    // The adjustment tx rolled back; protein unchanged, no audit row.
    assertThat(targetsRepository.findByUserId(userId).orElseThrow().getProteinTargetG())
        .isEqualByComparingTo(proteinBefore);
    assertThat(auditRepository.findAll()).isEmpty();
    assertThat(changeCapture.events()).isEmpty();
  }

  @Test
  void applyFeedback_belowConfidenceFloor_rejected_noChange() {
    UUID userId = UUID.randomUUID();
    seedTargetsWithSodium(userId);

    UUID feedbackId = UUID.randomUUID();
    NutritionFeedbackBridge.Input low =
        new NutritionFeedbackBridge.Input(
            feedbackId,
            userId,
            new BigDecimal("0.4"),
            "cut sodium",
            UUID.randomUUID(),
            payload("micro.sodium_mg", "decrease", "moderate"));

    NutritionFeedbackBridge.Result result = nutritionBridge.applyFeedback(low);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    assertThat(auditRepository.findAll()).isEmpty();
    assertThat(changeCapture.events()).isEmpty();
  }

  // ---------------- helpers ----------------

  private static NutritionFeedbackBridge.Input adjust(
      UUID feedbackId, UUID userId, String target, String direction, String magnitude) {
    return new NutritionFeedbackBridge.Input(
        feedbackId,
        userId,
        new BigDecimal("0.9"),
        "adjust " + target,
        UUID.randomUUID(),
        payload(target, direction, magnitude));
  }

  private static ObjectNode payload(String target, String direction, String magnitude) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("target", target);
    node.put("direction", direction);
    node.put("magnitude", magnitude);
    return node;
  }

  @Transactional
  NutritionTargets seedTargetsWithSodium(UUID userId) {
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
    return targetsRepository.saveAndFlush(t);
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class ChangeCaptureConfig {
    @Bean
    ChangeCapture changeCapture() {
      return new ChangeCapture();
    }
  }

  static class ChangeCapture {
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
