package com.example.mealprep.planner.domain.service.internal.stagec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link StageCInvokerImpl} — happy path, validation, all fallback triggers. */
@ExtendWith(MockitoExtension.class)
class StageCInvokerImplTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  @Mock private AiService aiService;

  private StageCInvokerImpl invoker() {
    return new StageCInvokerImpl(aiService, PlanTestData.scoringProperties());
  }

  private static PlanCompositionContext ctx() {
    return PlanTestData.minimalContext(List.of(), List.of());
  }

  @Test
  void happy_path_returns_llm_result_with_no_fallback() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(1, "candidate 1 is balanced"));

    StageCResult result =
        invoker()
            .pickOne(
                PlanTestData.twoCandidates(WEEK),
                PlanTestData.twoRollups(WEEK),
                ctx(),
                UUID.randomUUID());

    assertThat(result.chosenIndex()).isEqualTo(1);
    assertThat(result.reasoning()).isEqualTo("candidate 1 is balanced");
    assertThat(result.source()).isEqualTo(AugmentationSource.LLM);
    assertThat(result.fallback()).isFalse();
  }

  @Test
  void ai_unavailable_falls_back_to_deterministic_top_scored() {
    when(aiService.execute(any())).thenThrow(new AiUnavailableException("retries exhausted"));

    StageCResult result =
        invoker()
            .pickOne(
                PlanTestData.twoCandidates(WEEK),
                PlanTestData.twoRollups(WEEK),
                ctx(),
                UUID.randomUUID());

    assertThat(result.chosenIndex()).isZero();
    assertThat(result.reasoning())
        .isEqualTo("AI ranking unavailable; deterministic top-scored candidate selected.");
    assertThat(result.source()).isEqualTo(AugmentationSource.LLM);
    assertThat(result.fallback()).isTrue();
  }

  @Test
  void cost_cap_falls_back_to_deterministic() {
    when(aiService.execute(any()))
        .thenThrow(
            new AiCostBudgetExceededException(
                UUID.randomUUID(),
                new BigDecimal("1000"),
                new BigDecimal("1000"),
                Duration.ofDays(30),
                Duration.ofHours(1)));

    StageCResult result =
        invoker()
            .pickOne(
                PlanTestData.twoCandidates(WEEK),
                PlanTestData.twoRollups(WEEK),
                ctx(),
                UUID.randomUUID());

    assertThat(result.fallback()).isTrue();
    assertThat(result.chosenIndex()).isZero();
  }

  @Test
  void negative_index_response_falls_back() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(-1, "bad"));
    assertThat(
            invoker()
                .pickOne(
                    PlanTestData.twoCandidates(WEEK),
                    PlanTestData.twoRollups(WEEK),
                    ctx(),
                    UUID.randomUUID())
                .fallback())
        .isTrue();
  }

  @Test
  void one_past_end_index_falls_back() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(2, "bad")); // N == 2
    StageCResult r =
        invoker()
            .pickOne(
                PlanTestData.twoCandidates(WEEK),
                PlanTestData.twoRollups(WEEK),
                ctx(),
                UUID.randomUUID());
    assertThat(r.fallback()).isTrue();
    assertThat(r.chosenIndex()).isZero();
  }

  @Test
  void wildly_out_of_range_index_falls_back() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(1002, "bad"));
    assertThat(
            invoker()
                .pickOne(
                    PlanTestData.twoCandidates(WEEK),
                    PlanTestData.twoRollups(WEEK),
                    ctx(),
                    UUID.randomUUID())
                .fallback())
        .isTrue();
  }

  @Test
  void empty_candidates_returns_fallback_without_calling_ai() {
    StageCResult result = invoker().pickOne(List.of(), List.of(), ctx(), UUID.randomUUID());

    assertThat(result.fallback()).isTrue();
    assertThat(result.chosenIndex()).isZero();
    assertThat(result.reasoning()).isEqualTo("no candidates available");
    verify(aiService, never()).execute(any(AiTask.class));
  }

  @Test
  void size_mismatch_throws_illegal_argument() {
    List<CandidatePlan> candidates = PlanTestData.twoCandidates(WEEK);
    List<CandidatePlanRollupDto> oneRollup = List.of(PlanTestData.candidateRollup(WEEK, 2000));
    StageCInvokerImpl invoker = invoker();
    UUID trace = UUID.randomUUID();
    PlanCompositionContext context = ctx();
    assertThatThrownBy(() -> invoker.pickOne(candidates, oneRollup, context, trace))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same size");
  }

  @Test
  void no_transactional_annotation_on_pick_one() throws Exception {
    var method =
        StageCInvokerImpl.class.getDeclaredMethod(
            "pickOne", List.class, List.class, PlanCompositionContext.class, UUID.class);
    assertThat(
            method.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class))
        .isFalse();
    assertThat(
            StageCInvokerImpl.class.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class))
        .isFalse();
  }
}
