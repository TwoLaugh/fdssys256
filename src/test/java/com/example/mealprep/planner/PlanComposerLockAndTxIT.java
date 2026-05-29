package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.exception.ConcurrentGenerationInProgressException;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the two planner-generation concurrency/transaction invariants
 * (fix/planner-generation-tx-and-lock):
 *
 * <ul>
 *   <li><b>planner-1 (AI calls outside the tx):</b> when {@link PlanComposer#compose} is invoked
 *       the way the controller invokes it — with <b>no</b> surrounding transaction — Stage C and
 *       Phase 2 (the AI seams) execute with {@code
 *       TransactionSynchronizationManager.isActualTransactionActive() == false}; the persist +
 *       {@code PlanGeneratedEvent} publish run in one short tx and the plan commits {@code
 *       GENERATED}.
 *   <li><b>planner-2 (start-of-generation single-flight lease):</b> a second concurrent generate
 *       for the same {@code (household, week)} — while another holder owns the connection-free
 *       lease — fails with {@link ConcurrentGenerationInProgressException} (mapped to 409)
 *       <b>before any AI work runs</b> (Stage C is never invoked for the rejected request). A held
 *       lease on a <i>different</i> {@code (household, week)} does not contend. A normal generate
 *       releases its lease, so a subsequent generate for the same key succeeds.
 * </ul>
 *
 * <p>The deterministic stages are {@code @MockBean}ed exactly as in {@code PlanComposerIT} (same
 * context config → no context-cache thrash). Unlike {@code PlanComposerIT}, the happy-path call
 * here is made with <b>no</b> {@code tx().execute(...)} wrapper precisely so the no-active-tx
 * assertion is meaningful.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
    properties = "mealprep.planner.cold-start.enabled=false")
class PlanComposerLockAndTxIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(44).with(java.time.DayOfWeek.MONDAY);

  @Autowired private PlanComposer composer;
  @Autowired private PlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private LockService lockService;

  @MockBean private PlanCompositionContextBuilder contextBuilder;

  @MockBean
  private com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine
      beamSearchEngine;

  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;
  @MockBean private AdaptationService adaptationService;

  // AdaptationServiceImpl implements BOTH AdaptationService and AdaptationQueryService; @MockBean
  // on
  // one interface evicts the single shared impl bean. Mock the sibling too so the full context
  // loads.
  @MockBean private AdaptationQueryService adaptationQueryService;

  private final UUID slotId = UUID.randomUUID();
  private final UUID recipeId = UUID.randomUUID();

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
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM core_lock_leases");
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

  private CandidatePlan candidate() {
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK,
            SlotKind.DINNER,
            recipeId,
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

  private void wireDeterministicStages(UUID household) {
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(ctx(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate()), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));
  }

  private GeneratePlanRequest request(UUID household) {
    return new GeneratePlanRequest(household, WEEK, false);
  }

  /**
   * planner-1: the AI seams (Stage C + Phase 2) are invoked with no active transaction, and the
   * resulting plan still commits GENERATED with its scheduled recipe — i.e. the persist tx is the
   * only DB write boundary and the AI calls sit outside it.
   */
  @Test
  void aiStagesRunWithNoActiveTransaction_persistCommitsGenerated() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household);

    AtomicBoolean stageCSawActiveTx = new AtomicBoolean(true);
    AtomicBoolean phase2SawActiveTx = new AtomicBoolean(true);
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              stageCSawActiveTx.set(TransactionSynchronizationManager.isActualTransactionActive());
              return new StageCResult(0, "picked", AugmentationSource.LLM, false);
            });
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              phase2SawActiveTx.set(TransactionSynchronizationManager.isActualTransactionActive());
              return new com.example.mealprep.planner.api.dto.AugmentationResult(
                  List.<Augmentation>of(), List.<Augmentation>of(), List.of());
            });

    // Called WITHOUT a surrounding tx — exactly how PlansController.generate calls it.
    UUID planId = composer.compose(request(household), UUID.randomUUID(), null);

    assertThat(stageCSawActiveTx)
        .as("Stage C (AI pick) must run outside any transaction")
        .isFalse();
    assertThat(phase2SawActiveTx)
        .as("Phase 2 (AI augment) must run outside any transaction")
        .isFalse();

    tx().executeWithoutResult(
            t -> {
              Plan p = planRepository.findById(planId).orElseThrow();
              assertThat(p.getStatus()).isEqualTo(PlanStatus.GENERATED);
              assertThat(p.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
                  .isEqualTo(recipeId);
            });
  }

  /**
   * planner-2 (the whole point): a concurrent generate for the same (household, week) — while
   * another holder owns the connection-free lease — fails fast with
   * ConcurrentGenerationInProgressException (409) <b>before any AI work</b>. We assert Stage C (the
   * LLM pick) is NEVER invoked for the rejected request: the lease rejects the loser up front, so
   * no AI tokens are spent. The lease is held simply by calling {@code acquireLease} (it commits
   * and leaves a committed row — no held transaction needed), mirroring a first regenerate click
   * that is mid-pipeline when a second click arrives.
   */
  @Test
  void concurrentGenerateSameHouseholdWeek_throwsBeforeStageC() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household);

    // A concurrent generation holds the lease for (household, WEEK).
    LeaseHandle held =
        lockService
            .acquireLease(LockKey.forPlanWeek(household, WEEK), Duration.ofMinutes(10))
            .orElseThrow();
    try {
      assertThatThrownBy(() -> composer.compose(request(household), UUID.randomUUID(), null))
          .isInstanceOf(ConcurrentGenerationInProgressException.class);

      // The rejected request must NOT have run the AI pipeline — Stage C is never invoked.
      verify(stageCInvoker, never()).pickOne(any(), any(), any(), any());
      verify(phase2Augmenter, never()).augment(any(), any(), any(), any());
    } finally {
      lockService.releaseLease(held);
    }
  }

  /**
   * planner-2: a held lease on a DIFFERENT (household, week) does not contend — a generate for an
   * unrelated household/week proceeds and commits GENERATED while the other lease is held.
   */
  @Test
  void concurrentGenerateDifferentHouseholdWeek_doesNotContend() {
    UUID lockedHousehold = UUID.randomUUID();
    UUID generatingHousehold = UUID.randomUUID();
    wireDeterministicStages(generatingHousehold);

    LeaseHandle held =
        lockService
            .acquireLease(LockKey.forPlanWeek(lockedHousehold, WEEK), Duration.ofMinutes(10))
            .orElseThrow();
    UUID planId;
    try {
      // Different household → different lease key → no contention.
      planId = composer.compose(request(generatingHousehold), UUID.randomUUID(), null);
      assertThat(planId).isNotNull();
    } finally {
      lockService.releaseLease(held);
    }

    Plan p = planRepository.findById(planId).orElseThrow();
    assertThat(p.getStatus()).isEqualTo(PlanStatus.GENERATED);
  }

  /**
   * planner-2: a normal generate releases its lease on completion (the finally block), so a
   * subsequent generate for the same (household, week) succeeds rather than 409-ing on a leaked
   * lease.
   */
  @Test
  void normalGenerate_releasesLease_soNextGenerateSucceeds() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household);

    UUID first = composer.compose(request(household), UUID.randomUUID(), null);
    assertThat(first).isNotNull();

    // No lease row should linger for the key after a normal completion.
    Integer leaseRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_lock_leases WHERE lock_key = ?",
            Integer.class,
            LockKey.forPlanWeek(household, WEEK).serialize());
    assertThat(leaseRows).isZero();

    // A second generate for the same key succeeds (lease was freed).
    UUID second = composer.compose(request(household), UUID.randomUUID(), null);
    assertThat(second).isNotNull();
  }
}
