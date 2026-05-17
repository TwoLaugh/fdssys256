package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCPickResponse;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Stage-C IT. Verifies the data-flow seam: 01f's {@link RollupBuilder} produces a realistic
 * per-candidate rollup, that rollup feeds {@link StageCInvoker}, and the invoker's happy /
 * AiUnavailable / transient-failure paths behave per the ticket.
 *
 * <p>{@code TestAiService} (the {@code @Primary @Profile("test")} bean) can only return canned
 * responses — it cannot simulate {@link AiUnavailableException}. {@code @MockBean AiService}
 * replaces it (the established pattern, see {@code AdaptationAiUnavailableE2EIT}) so each path can
 * be stubbed precisely. {@code @ActiveProfiles("test")} keeps the rest of the wiring intact.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class StageCInvokerIT {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  @Autowired private StageCInvoker invoker;
  @Autowired private RollupBuilder rollupBuilder;
  @MockBean private AiService aiService;

  private record Fixture(
      List<CandidatePlan> candidates,
      List<CandidatePlanRollupDto> rollups,
      PlanCompositionContext ctx) {}

  /** Builds two candidates and derives each one's rollup via 01f's RollupBuilder. */
  private Fixture fixture() {
    UUID recipeA = UUID.randomUUID();
    UUID recipeB = UUID.randomUUID();
    RecipeDto rA = PlanTestData.scoredRecipe(recipeA, 30, "Thai", "tofu", "fry", List.of("rice"));
    RecipeDto rB =
        PlanTestData.scoredRecipe(recipeB, 25, "Italian", "beef", "bake", List.of("pasta"));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(rA, rB));

    List<SlotAssignment> asgA = new ArrayList<>();
    List<SlotAssignment> asgB = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      asgA.add(PlanTestData.assignment(UUID.randomUUID(), recipeA, WEEK.plusDays(d), 0, 2));
      asgB.add(PlanTestData.assignment(UUID.randomUUID(), recipeB, WEEK.plusDays(d), 0, 2));
    }
    CandidatePlan cA = PlanTestData.candidatePlan(WEEK, asgA);
    CandidatePlan cB = PlanTestData.candidatePlan(WEEK, asgB);

    List<CandidatePlanRollupDto> rollups =
        List.of(toDto(rollupBuilder.build(cA, ctx)), toDto(rollupBuilder.build(cB, ctx)));
    return new Fixture(List.of(cA, cB), rollups, ctx);
  }

  /**
   * Adapts 01f's planner {@link RollupSummaryDocument} to nutrition's {@link
   * CandidatePlanRollupDto} — the seam the composer (01j) owns in production.
   */
  private static CandidatePlanRollupDto toDto(RollupSummaryDocument doc) {
    List<CandidateDailyRollupDto> perDay = new ArrayList<>();
    for (DailyRollupDocument d : doc.daily()) {
      perDay.add(
          new CandidateDailyRollupDto(
              d.date(),
              ActivityLevel.LIGHT_ACTIVITY,
              d.kcal(),
              d.proteinG(),
              d.carbsG(),
              d.fatG(),
              d.fibreG(),
              Map.of()));
    }
    return new CandidatePlanRollupDto(
        perDay.get(0).date(), perDay.get(perDay.size() - 1).date(), perDay);
  }

  @Test
  void happy_path_uses_llm_choice() {
    when(aiService.execute(any()))
        .thenReturn(new StageCPickResponse(1, "candidate 1 has better variety"));
    Fixture f = fixture();

    StageCResult result = invoker.pickOne(f.candidates(), f.rollups(), f.ctx(), UUID.randomUUID());

    assertThat(result.chosenIndex()).isEqualTo(1);
    assertThat(result.reasoning()).isEqualTo("candidate 1 has better variety");
    assertThat(result.source()).isEqualTo(AugmentationSource.LLM);
    assertThat(result.fallback()).isFalse();
  }

  @Test
  void ai_unavailable_path_falls_back() {
    when(aiService.execute(any())).thenThrow(new AiUnavailableException("cost cap / key missing"));
    Fixture f = fixture();

    StageCResult result = invoker.pickOne(f.candidates(), f.rollups(), f.ctx(), UUID.randomUUID());

    assertThat(result.fallback()).isTrue();
    assertThat(result.chosenIndex()).isZero();
    assertThat(result.reasoning())
        .isEqualTo("AI ranking unavailable; deterministic top-scored candidate selected.");
  }

  @Test
  void transient_failure_path_falls_back() {
    // A transient failure surfaces as AiUnavailableException after the dispatcher's retries.
    when(aiService.execute(any()))
        .thenThrow(new AiUnavailableException("503 after retries", new RuntimeException("boom")));
    Fixture f = fixture();

    StageCResult result = invoker.pickOne(f.candidates(), f.rollups(), f.ctx(), UUID.randomUUID());

    assertThat(result.fallback()).isTrue();
    assertThat(result.chosenIndex()).isZero();
  }

  @Test
  void out_of_range_llm_index_falls_back() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(99, "nonsense"));
    Fixture f = fixture();

    StageCResult result = invoker.pickOne(f.candidates(), f.rollups(), f.ctx(), UUID.randomUUID());

    assertThat(result.fallback()).isTrue();
    assertThat(result.chosenIndex()).isZero();
  }
}
