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
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  // ---- mutation-killing additions -------------------------------------------------------------

  private static HardConstraintsDto hc(UUID userId) {
    return new HardConstraintsDto(
        UUID.randomUUID(), userId, List.of(), null, List.of(), List.of(), List.of(), 0L);
  }

  private PlanCompositionContext ctxWithMembers(Map<UUID, HardConstraintsDto> members) {
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK,
        List.of(),
        members,
        Map.of(),
        null,
        null,
        null,
        new com.example.mealprep.planner.api.dto.RecipePoolSnapshot(
            List.of(), Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  @SuppressWarnings("rawtypes")
  private StageCPickTask dispatchedTask() {
    ArgumentCaptor<AiTask> cap = ArgumentCaptor.forClass(AiTask.class);
    verify(aiService).execute(cap.capture());
    return (StageCPickTask) cap.getValue();
  }

  /**
   * A valid Stage-C chosenIndex of 0 must be returned as an LLM (non-fallback) result, NOT routed
   * through the deterministic fallback. Kills the L90 ConditionalsBoundary mutant {@code
   * chosenIndex < 0} → {@code <= 0}: under {@code <=} a legitimate index 0 would be treated as
   * out-of-range and replaced by the fallback (same index, but fallback=true + different
   * reasoning).
   */
  @Test
  void chosen_index_zero_is_a_valid_llm_pick_not_fallback() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "first is best"));

    StageCResult result =
        invoker()
            .pickOne(
                PlanTestData.twoCandidates(WEEK),
                PlanTestData.twoRollups(WEEK),
                ctx(),
                UUID.randomUUID());

    assertThat(result.chosenIndex()).isZero();
    assertThat(result.fallback()).isFalse();
    assertThat(result.reasoning()).isEqualTo("first is best");
  }

  /**
   * No household-settings document → household size falls back to the hard-constraint member count
   * (2 members → size 2). Kills the L141 NegateConditionals and L142 PrimitiveReturns ({@code
   * Math.max(1, members)} → 0) by asserting the exact dispatched {@code household_size}.
   */
  @Test
  void household_size_falls_back_to_member_count() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "ok"));
    UUID u1 = new UUID(0L, 1L);
    UUID u2 = new UUID(0L, 2L);
    PlanCompositionContext c = ctxWithMembers(Map.of(u1, hc(u1), u2, hc(u2)));

    invoker()
        .pickOne(
            PlanTestData.twoCandidates(WEEK), PlanTestData.twoRollups(WEEK), c, UUID.randomUUID());

    StageCPickTask task = dispatchedTask();
    assertThat(task.variables()).containsEntry("household_size", 2);
  }

  /**
   * No members at all → household size floors at 1. Kills the L142 PrimitiveReturns ({@code
   * Math.max(1, 0)} must be 1, not 0) and the L141 member-count branch.
   */
  @Test
  void household_size_floors_at_one_when_no_members() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "ok"));
    PlanCompositionContext c = ctxWithMembers(Map.of());

    invoker()
        .pickOne(
            PlanTestData.twoCandidates(WEEK), PlanTestData.twoRollups(WEEK), c, UUID.randomUUID());

    assertThat(dispatchedTask().variables()).containsEntry("household_size", 1);
  }

  /**
   * Primary user = the lowest-by-natural-order hard-constraint member id. Kills the L152
   * NegateConditionals (the empty-map guard must be skipped when members exist) and L155
   * NullReturnVals (a real, deterministic, non-null user id is dispatched on the task).
   */
  @Test
  void primary_user_is_lowest_member_id() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "ok"));
    UUID low = new UUID(0L, 1L);
    UUID high = new UUID(0L, 2L);
    PlanCompositionContext c = ctxWithMembers(Map.of(high, hc(high), low, hc(low)));

    invoker()
        .pickOne(
            PlanTestData.twoCandidates(WEEK), PlanTestData.twoRollups(WEEK), c, UUID.randomUUID());

    assertThat(dispatchedTask().userId()).contains(low);
  }

  /**
   * No members → primary user resolves to null → the task carries an empty user id (system-
   * initiated tolerance). Kills the L152 NegateConditionals (other arm) — the empty-map guard must
   * short-circuit to null rather than touching an empty key set.
   */
  @Test
  void primary_user_empty_when_no_members() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "ok"));
    PlanCompositionContext c = ctxWithMembers(Map.of());

    invoker()
        .pickOne(
            PlanTestData.twoCandidates(WEEK), PlanTestData.twoRollups(WEEK), c, UUID.randomUUID());

    assertThat(dispatchedTask().userId()).isEmpty();
  }

  /**
   * The constraints summary embeds the resolved household size and the member-profile count. Kills
   * the L121 NegateConditionals ({@code hardConstraintsByUserId == null}) and L122
   * EmptyObjectReturnVals (the summary string is built, not blanked) by asserting the exact text.
   */
  @Test
  void constraints_summary_embeds_size_and_member_count() {
    when(aiService.execute(any())).thenReturn(new StageCPickResponse(0, "ok"));
    UUID u1 = new UUID(0L, 1L);
    UUID u2 = new UUID(0L, 2L);
    PlanCompositionContext c = ctxWithMembers(Map.of(u1, hc(u1), u2, hc(u2)));

    invoker()
        .pickOne(
            PlanTestData.twoCandidates(WEEK), PlanTestData.twoRollups(WEEK), c, UUID.randomUUID());

    Object summary = dispatchedTask().variables().get("constraints_summary");
    assertThat(String.valueOf(summary))
        .contains("Household of 2 people")
        .contains("2 member hard-constraint profile(s)")
        .contains("week starting " + WEEK);
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
