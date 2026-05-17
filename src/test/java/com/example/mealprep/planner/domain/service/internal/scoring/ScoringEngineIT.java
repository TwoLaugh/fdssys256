package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-context IT: under the default {@code test} profile (no {@code scoring-stub}) the real {@link
 * ScoringEngineImpl} must be the resolved {@link ScoringEngine} bean, and Spring must auto-wire all
 * seven {@code SubScoreCalculator}s plus both gates with the real {@code PlannerProperties} bound
 * from {@code application.properties}.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class ScoringEngineIT {

  @Autowired private ScoringEngine scoringEngine;
  @Autowired private List<SubScoreCalculator> calculators;
  @Autowired private NutritionFloorGate nutritionFloorGate;
  @Autowired private VarietyGate varietyGate;

  @Test
  void real_impl_wins_over_stub_in_default_test_profile() {
    assertThat(scoringEngine).isInstanceOf(ScoringEngineImpl.class);
  }

  @Test
  void all_seven_calculators_are_component_scanned() {
    assertThat(calculators)
        .extracting(SubScoreCalculator::name)
        .containsExactlyInAnyOrder(
            "preference", "nutrition", "cost", "variety", "time", "batch", "provisions");
  }

  @Test
  void both_gates_are_wired() {
    assertThat(nutritionFloorGate).isNotNull();
    assertThat(varietyGate).isNotNull();
  }
}
