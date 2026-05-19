package com.example.mealprep.planner.domain.service.internal.stagec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.planner.api.dto.AugmentationProposal;
import com.example.mealprep.planner.api.dto.AugmentationResult;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.RefineDirectiveProposal;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link Phase2AugmenterImpl} — pure Mockito, no Spring context. Covers ticket
 * planner-01h §"Edge-case checklist": happy path, over-limit capping, empty payload, the two
 * fallback paths, and the always-empty {@code emittedDirectives} (cross-classpath deferral).
 */
@ExtendWith(MockitoExtension.class)
class Phase2AugmenterImplTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  @Mock private AiService aiService;
  @Mock private HardConstraintFilterService filter;

  private Phase2AugmenterImpl augmenter;

  private UUID slotId;
  private UUID recipeId;
  private PlanCompositionContext ctx;

  @BeforeEach
  void setUp() {
    AugmentationParser parser = new AugmentationParser();
    PlannerProperties properties = PlanTestData.scoringProperties();
    // Real verifier with a controllable filter mock — exercises the augmenter↔verifier wiring;
    // AugmentationVerifierTest covers the constraint logic itself.
    AugmentationVerifier verifier = new AugmentationVerifier(filter, properties);
    lenient()
        .when(filter.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient()
        .when(filter.check(any(UUID.class), anyList()))
        .thenReturn(new FilterResult(true, List.of()));

    augmenter = new Phase2AugmenterImpl(aiService, verifier, parser, properties);

    slotId = UUID.randomUUID();
    recipeId = UUID.randomUUID();
    RecipeDto pooledRecipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 15, List.of(), List.of("oats"));
    MealSlotSkeleton slot =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK_START,
            SlotKind.SNACK,
            "snack",
            30,
            true,
            new ArrayList<>(List.of(UUID.randomUUID())));
    ctx =
        new PlanCompositionContext(
            UUID.randomUUID(),
            WEEK_START,
            List.of(slot),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(List.of(pooledRecipe), Instant.parse("2026-01-01T00:00:00Z")),
            List.of(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of());
  }

  private CandidatePlan chosenPlan() {
    return new CandidatePlan(UUID.randomUUID(), WEEK_START, List.of(), null);
  }

  private CandidatePlanRollupDto rollup() {
    return new CandidatePlanRollupDto(
        WEEK_START,
        WEEK_START,
        List.of(
            new CandidateDailyRollupDto(
                WEEK_START,
                ActivityLevel.LIGHT_ACTIVITY,
                2000,
                new BigDecimal("120"),
                new BigDecimal("200"),
                new BigDecimal("60"),
                new BigDecimal("30"),
                Map.of())));
  }

  private static AugmentationProposal addSnack(UUID slot, UUID recipe) {
    return new AugmentationProposal(
        "ADD_SNACK", slot, recipe, 1, null, null, null, null, "fill protein gap");
  }

  @SuppressWarnings("unchecked")
  private void registerResponse(Phase2AugmentationResponse response) {
    when(aiService.execute(any(AiTask.class))).thenReturn(response);
  }

  @Test
  void happyPath_threeAugmentationsAllPass_returnsThreeApplied() {
    registerResponse(
        new Phase2AugmentationResponse(
            List.of(
                addSnack(slotId, recipeId), addSnack(slotId, recipeId), addSnack(slotId, recipeId)),
            List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).hasSize(3);
    assertThat(result.discardedByVerifier()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void sevenAugmentations_exceedsLimitFive_onlyFirstFiveProcessed() {
    List<AugmentationProposal> seven = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      seven.add(addSnack(slotId, recipeId));
    }
    registerResponse(new Phase2AugmentationResponse(seven, List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied().size() + result.discardedByVerifier().size()).isEqualTo(5);
  }

  @Test
  void zeroAugmentations_returnsEmptyResult() {
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.discardedByVerifier()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void hallucinatedRecipe_discardedByVerifier_notApplied() {
    registerResponse(
        new Phase2AugmentationResponse(
            List.of(addSnack(slotId, UUID.randomUUID())), List.of())); // recipe not in pool

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.discardedByVerifier()).hasSize(1);
  }

  @Test
  void malformedProposalsDroppedByParser_beforeVerifier() {
    AugmentationProposal bad =
        new AugmentationProposal("ADD_SNACK", slotId, null, null, null, null, null, null, "x");
    registerResponse(
        new Phase2AugmentationResponse(List.of(bad, addSnack(slotId, recipeId)), List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).hasSize(1);
    assertThat(result.discardedByVerifier()).isEmpty();
  }

  @Test
  void refineDirectivesAlwaysEmpty_crossClasspathDeferral() {
    registerResponse(
        new Phase2AugmentationResponse(
            List.of(),
            List.of(
                new RefineDirectiveProposal(
                    "SUBSTITUTE_INGREDIENT", slotId, "butter", "ghee", null, null, "swap"),
                new RefineDirectiveProposal("REDUCE_TIME", slotId, null, null, 60, 30, "faster"))));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void aiUnavailable_returnsEmptyResult_noVerification() {
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiUnavailableException("cost cap reached"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.discardedByVerifier()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void aiInvalidResponse_returnsEmptyResult() {
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiInvalidResponseException("unparseable payload"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void taskDispatched_isPhase2AugmentationTask_withCorrectWiring() {
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));

    augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
    verify(aiService).execute(captor.capture());
    AiTask<?> dispatched = captor.getValue();
    assertThat(dispatched).isInstanceOf(Phase2AugmentationTask.class);
    assertThat(dispatched.type()).isEqualTo(TaskType.PLANNER_PHASE2_AUGMENTATION);
    assertThat(dispatched.prompt().name()).isEqualTo("planner/phase2-augmentation");
    assertThat(dispatched.prompt().version()).isEqualTo(1);
    assertThat(dispatched.variables())
        .containsKeys(
            "chosen_plan",
            "constraints_summary",
            "nutrition_gaps",
            "max_augmentations",
            "max_refine_directives");
    assertThat(dispatched.tools()).isPresent();
  }

  @Test
  void aiFailedBeforeResponse_noAugmentationApplied_evenIfVerifierWouldAccept() {
    when(aiService.execute(any(AiTask.class))).thenThrow(new AiUnavailableException("down"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    verify(aiService, times(1)).execute(any(AiTask.class));
  }

  // ---- nutrition-gap computation (computeNutritionGaps / addGapIfBreached) ---------------------

  private static MacroTargetDto macro(String targetG, EnforcementDirection dir) {
    return new MacroTargetDto(new BigDecimal(targetG), null, "daily", dir);
  }

  private static TargetsDto targets(
      UUID user, MacroTargetDto protein, MacroTargetDto carbs, MacroTargetDto fat) {
    return new TargetsDto(
        UUID.randomUUID(),
        user,
        Goal.MAINTAIN,
        null,
        protein,
        carbs,
        fat,
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  /** Rebuild the fixture context pinning {@code primaryUser} as the lead slot eater + targets. */
  private PlanCompositionContext ctxWithTargets(UUID primaryUser, TargetsDto t) {
    RecipeDto pooledRecipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 15, List.of(), List.of("oats"));
    MealSlotSkeleton slot =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK_START,
            SlotKind.SNACK,
            "snack",
            30,
            true,
            new ArrayList<>(List.of(primaryUser)));
    return new PlanCompositionContext(
        UUID.randomUUID(),
        WEEK_START,
        List.of(slot),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(List.of(pooledRecipe), Instant.parse("2026-01-01T00:00:00Z")),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of(primaryUser, t));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> dispatchedGaps() {
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
    verify(aiService).execute(captor.capture());
    Object gaps = captor.getValue().variables().get("nutrition_gaps");
    return (List<Map<String, Object>>) gaps;
  }

  /**
   * LOWER_FLOOR protein target 150 with actual 120 (rollup) → breached (cmp &lt; 0). The dispatched
   * task's {@code nutrition_gaps} must carry exactly one protein gap. Kills the L244 VoidMethodCall
   * (the protein {@code addGapIfBreached} call), L235/L239/L241 NegateConditionals (the
   * null/targets guards must NOT short-circuit) and L265 LOWER_FLOOR {@code cmp < 0} mutants.
   */
  @Test
  void lowerFloorProteinBelowTarget_emitsProteinGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, macro("150", EnforcementDirection.LOWER_FLOOR), null, null));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    List<Map<String, Object>> gaps = dispatchedGaps();
    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0)).containsEntry("macro", "protein");
  }

  /**
   * LOWER_FLOOR protein target 100 with actual 120 → NOT breached → no gap. Pairs with the test
   * above to pin the exact {@code cmp < 0} boundary (kills the L265 ConditionalsBoundary mutant).
   */
  @Test
  void lowerFloorProteinAtOrAboveTarget_noGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, macro("100", EnforcementDirection.LOWER_FLOOR), null, null));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    assertThat(dispatchedGaps()).isEmpty();
  }

  /**
   * UPPER_LIMIT carbs target 150 with actual 200 → breached (cmp &gt; 0) → one carbs gap. Kills the
   * L245 VoidMethodCall (carbs {@code addGapIfBreached}) and the L266 UPPER_LIMIT {@code cmp > 0}
   * boundary/negate mutants.
   */
  @Test
  void upperLimitCarbsAboveTarget_emitsCarbsGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, null, macro("150", EnforcementDirection.UPPER_LIMIT), null));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    List<Map<String, Object>> gaps = dispatchedGaps();
    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0)).containsEntry("macro", "carbs");
  }

  /**
   * BOTH_BOUNDED fat target 60 with actual exactly 60 → cmp == 0 → NOT breached (the {@code cmp !=
   * 0} arm). Kills the L267 NegateConditionals on the BOTH_BOUNDED branch.
   */
  @Test
  void bothBoundedFatExactlyOnTarget_noGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, null, null, macro("60", EnforcementDirection.BOTH_BOUNDED)));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    assertThat(dispatchedGaps()).isEmpty();
  }

  /**
   * BOTH_BOUNDED fat target 50 with actual 60 → cmp != 0 → breached → one fat gap. Kills the L246
   * VoidMethodCall (fat {@code addGapIfBreached}) and confirms the BOTH_BOUNDED breach arm.
   */
  @Test
  void bothBoundedFatOffTarget_emitsFatGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, null, null, macro("50", EnforcementDirection.BOTH_BOUNDED)));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    List<Map<String, Object>> gaps = dispatchedGaps();
    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0)).containsEntry("macro", "fat");
  }

  /**
   * No nutrition targets row for the primary user → {@code computeNutritionGaps} returns empty
   * (L240/L241 guard). Kills the L241 EmptyObjectReturnVals/Negate by asserting an empty gap list
   * is still dispatched (not a fabricated gap).
   */
  @Test
  void noTargetsForPrimaryUser_emptyGaps() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    // context has the user as eater but NO nutritionByUserId entry
    RecipeDto pooledRecipe =
        PlanTestData.recipeFor(recipeId, SlotKind.SNACK, 15, List.of(), List.of("oats"));
    MealSlotSkeleton slot =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            slotId,
            0,
            WEEK_START,
            SlotKind.SNACK,
            "snack",
            30,
            true,
            new ArrayList<>(List.of(user)));
    PlanCompositionContext c =
        new PlanCompositionContext(
            UUID.randomUUID(),
            WEEK_START,
            List.of(slot),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(List.of(pooledRecipe), Instant.parse("2026-01-01T00:00:00Z")),
            List.of(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of());

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    assertThat(dispatchedGaps()).isEmpty();
  }

  /**
   * LOWER_FLOOR target EXACTLY equal to the actual (protein 120 vs rollup 120) → cmp == 0 → NOT
   * breached. Kills the L265 ConditionalsBoundary mutant {@code cmp < 0} → {@code cmp <= 0}: under
   * {@code <=} an on-target value would wrongly be flagged as a gap.
   */
  @Test
  void lowerFloorProteinExactlyOnTarget_noGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, macro("120", EnforcementDirection.LOWER_FLOOR), null, null));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    assertThat(dispatchedGaps()).isEmpty();
  }

  /**
   * UPPER_LIMIT target EXACTLY equal to the actual (carbs 200 vs rollup 200) → cmp == 0 → NOT
   * breached. Kills the L266 ConditionalsBoundary mutant {@code cmp > 0} → {@code cmp >= 0}.
   */
  @Test
  void upperLimitCarbsExactlyOnTarget_noGap() {
    UUID user = UUID.randomUUID();
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));
    PlanCompositionContext c =
        ctxWithTargets(
            user, targets(user, null, macro("200", EnforcementDirection.UPPER_LIMIT), null));

    augmenter.augment(chosenPlan(), rollup(), c, UUID.randomUUID());

    assertThat(dispatchedGaps()).isEmpty();
  }

  /**
   * The dispatched constraints-summary string must reflect the context's member/slot/pool counts
   * and week. Kills the L192/L193/L195 NegateConditionals (the null guards must take the real-count
   * arm) and the L198 EmptyObjectReturnVals (the summary is assembled, not blanked).
   */
  @Test
  void constraintsSummary_reflectsContextCounts() {
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));

    augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
    verify(aiService).execute(captor.capture());
    Object summary = captor.getValue().variables().get("constraints_summary");
    assertThat(String.valueOf(summary))
        .contains("household members with hard constraints: 0")
        .contains("slots: 1")
        .contains("recipe pool size: 1")
        .contains("week starting " + WEEK_START);
  }

  @Test
  void plainBeanCall_works_documentsAiCallOutsideTransaction() {
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result).isNotNull();
    verify(aiService, never()).embed(any());
  }
}
