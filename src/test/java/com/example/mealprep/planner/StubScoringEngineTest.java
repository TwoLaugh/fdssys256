package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.scoring.ScoringEngine;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the test-profile-gated {@code StubScoringEngine} is the {@link ScoringEngine} the Spring
 * context resolves under {@code @ActiveProfiles("test")} — required for the beam-search unit tests
 * to depend on a deterministic SPI — and that its output is byte-identical for the same input.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles({"test", "scoring-stub"})
class StubScoringEngineTest {

  @Autowired private ScoringEngine engine;

  @Test
  void stub_is_active_in_test_profile() {
    assertThat(engine.getClass().getSimpleName()).isEqualTo("StubScoringEngine");
  }

  @Test
  void same_plan_produces_byte_identical_score() {
    CandidatePlan plan = candidatePlan(List.of(uuidOf(1), uuidOf(2), uuidOf(3)));
    ScoreResult a = engine.score(plan, dummyContext());
    ScoreResult b = engine.score(plan, dummyContext());
    assertThat(a.composite()).isEqualByComparingTo(b.composite());
    assertThat(a.breakdown().composite()).isEqualByComparingTo(b.breakdown().composite());
  }

  @Test
  void different_recipe_sets_produce_different_scores() {
    CandidatePlan a = candidatePlan(List.of(uuidOf(1), uuidOf(2)));
    CandidatePlan b = candidatePlan(List.of(uuidOf(3), uuidOf(4)));
    assertThat(engine.score(a, dummyContext()).composite())
        .isNotEqualByComparingTo(engine.score(b, dummyContext()).composite());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static CandidatePlan candidatePlan(List<UUID> recipeIds) {
    LocalDate weekStart = LocalDate.of(2026, 1, 5);
    List<SlotAssignment> assignments = new ArrayList<>();
    int idx = 0;
    for (UUID r : recipeIds) {
      assignments.add(
          new SlotAssignment(
              UUID.randomUUID(),
              UUID.randomUUID(),
              idx++,
              weekStart,
              SlotKind.DINNER,
              r,
              UUID.randomUUID(),
              UUID.randomUUID(),
              2,
              false));
    }
    return new CandidatePlan(UUID.randomUUID(), weekStart, assignments, null);
  }

  private static PlanCompositionContext dummyContext() {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        LocalDate.of(2026, 1, 5),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(List.of(), Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private static UUID uuidOf(int seed) {
    return new UUID(0L, seed);
  }
}
