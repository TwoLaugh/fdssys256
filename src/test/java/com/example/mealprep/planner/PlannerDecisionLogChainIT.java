package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RefineDirectiveDto;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * planner-01l cross-stage decision-log smoke test (ticket invariant #9). Generates one plan
 * end-to-end through the real {@link PlanComposer} over a real Postgres (deterministic stages
 * {@code @MockBean}ed, exactly as {@code PlanComposerIT}), then reads back {@code decision_log}
 * scoped to {@code scope_kind=PLANNER AND scope_id=planId} via the real {@link
 * DecisionLogQueryService#getByScope} and asserts:
 *
 * <ul>
 *   <li>exactly one {@code PLAN_GENERATION_START} row;
 *   <li>every other row is reachable from START via the {@code parentDecisionId} chain (a single
 *       connected DAG);
 *   <li>{@code STAGE_C_DONE.inputs.rollupCount} equals Stage A's {@code topNRecipeIds.length};
 *   <li>every row has a non-null {@code trace_id} and they all match.
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
// Mocked context builder (empty pool) → disable the recipe-pool Tier-2 cold-start gate so it does
// not fire on the empty pool and invoke the discovery runner (out of scope for this decision-log
// smoke test; the gate has its own coverage in ColdStartGateTest / PlannerColdStartIT).
@org.springframework.test.context.TestPropertySource(
    properties = "mealprep.planner.cold-start.enabled=false")
class PlannerDecisionLogChainIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(45).with(java.time.DayOfWeek.MONDAY);

  @Autowired private PlanComposer composer;
  @Autowired private DecisionLogQueryService decisionLogQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockBean private PlanCompositionContextBuilder contextBuilder;

  @MockBean
  private com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine
      beamSearchEngine;

  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;
  @MockBean private AdaptationService adaptationService;
  // AdaptationServiceImpl implements AdaptationService + AdaptationQueryService; @MockBean on one
  // evicts the shared impl bean (wave-3 multi-interface @MockBean eviction) — mock the sibling.
  @MockBean private AdaptationQueryService adaptationQueryService;

  private final UUID slotId = UUID.randomUUID();
  private final UUID originalRecipeId = UUID.randomUUID();

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_plan_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    // Single DELETE — decision_log self-FKs on parent_decision_id.
    jdbcTemplate.update("DELETE FROM decision_log");
  }

  private PlanCompositionContext ctx(UUID household) {
    return new PlanCompositionContext(
        household,
        WEEK,
        List.of(),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(List.of(), Instant.now()),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private CandidatePlan oneSlotCandidate() {
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK,
            SlotKind.DINNER,
            originalRecipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    return new CandidatePlan(
        UUID.randomUUID(),
        WEEK,
        List.of(a),
        new ScoreResult(BigDecimal.ONE, PlanTestData.zeroScoreBreakdown()));
  }

  @Test
  void singlePlanGeneration_writesAConnectedPlannerDecisionDag() {
    UUID household = UUID.randomUUID();
    PlanCompositionContext context = ctx(household);
    CandidatePlan candidate = oneSlotCandidate();
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(context);
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked", AugmentationSource.LLM, false));
    // Phase 2 emits one refine-directive so Stage D runs and writes a STAGE_D_OUTCOME row.
    RefineDirectiveDto directive =
        new RefineDirectiveDto("SUBSTITUTE_INGREDIENT", slotId, "swap", "rice", "quinoa");
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of(directive)));
    when(adaptationService.runPlanTimeRefineJob(any()))
        .thenReturn(
            new AdaptationResultDto(
                UUID.randomUUID(),
                originalRecipeId,
                AdaptationClassification.NO_CHANGE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                null,
                "stubbed",
                "",
                false,
                List.of(),
                UUID.randomUUID(),
                BigDecimal.ONE));

    UUID planId =
        tx().execute(
                t ->
                    composer.compose(
                        new GeneratePlanRequest(household, WEEK, false), UUID.randomUUID(), null));

    List<DecisionLogDto> rows = decisionLogQueryService.getByScope("PLANNER", planId);
    assertThat(rows).as("planner decision rows for this plan").isNotEmpty();

    // (a) Exactly one PLAN_GENERATION_START.
    long starts = rows.stream().filter(r -> "PLAN_GENERATION_START".equals(kindOf(r))).count();
    assertThat(starts).as("exactly one PLAN_GENERATION_START").isEqualTo(1);

    DecisionLogDto start =
        rows.stream()
            .filter(r -> "PLAN_GENERATION_START".equals(kindOf(r)))
            .findFirst()
            .orElseThrow();
    assertThat(start.parentDecisionId()).as("START is the trace root").isNull();

    // (b) Single connected DAG: every non-root row is reachable from START via parentDecisionId.
    Map<UUID, DecisionLogDto> byId = new HashMap<>();
    for (DecisionLogDto r : rows) {
      byId.put(r.decisionId(), r);
    }
    Set<UUID> reachable = new HashSet<>();
    reachable.add(start.decisionId());
    boolean grew = true;
    while (grew) {
      grew = false;
      for (DecisionLogDto r : rows) {
        if (!reachable.contains(r.decisionId())
            && r.parentDecisionId() != null
            && reachable.contains(r.parentDecisionId())) {
          reachable.add(r.decisionId());
          grew = true;
        }
      }
    }
    assertThat(reachable)
        .as("all rows reachable from START — a single connected DAG")
        .hasSize(rows.size());

    // (c) STAGE_C_DONE.inputs.rollupCount == Stage A topNRecipeIds.length.
    DecisionLogDto stageA =
        rows.stream().filter(r -> "STAGE_A_DONE".equals(kindOf(r))).findFirst().orElseThrow();
    DecisionLogDto stageC =
        rows.stream().filter(r -> "STAGE_C_DONE".equals(kindOf(r))).findFirst().orElseThrow();
    int topN = stageA.chosen().get("topNRecipeIds").size();
    int rollupCount = stageC.inputs().get("rollupCount").asInt();
    assertThat(rollupCount)
        .as("STAGE_C rollupCount matches the Stage A candidate count")
        .isEqualTo(topN)
        .isEqualTo(1);

    // (d) Every row has a non-null trace_id and they all match.
    UUID trace = start.traceId();
    assertThat(trace).isNotNull();
    assertThat(rows).allSatisfy(r -> assertThat(r.traceId()).isEqualTo(trace));

    // Stage D ran -> a STAGE_D_OUTCOME row exists and chains into the DAG.
    assertThat(rows).anyMatch(r -> "STAGE_D_OUTCOME".equals(kindOf(r)));
    // Composer exit recorded.
    assertThat(rows).anyMatch(r -> "PLAN_GENERATION_COMPLETE".equals(kindOf(r)));
  }

  /** The planner kind is stamped into inputs.kind by DecisionLogWriter (no kind column). */
  private static String kindOf(DecisionLogDto row) {
    return row.inputs() == null || row.inputs().get("kind") == null
        ? null
        : row.inputs().get("kind").asText();
  }
}
