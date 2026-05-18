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
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
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

  @Test
  void plainBeanCall_works_documentsAiCallOutsideTransaction() {
    registerResponse(new Phase2AugmentationResponse(List.of(), List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result).isNotNull();
    verify(aiService, never()).embed(any());
  }
}
