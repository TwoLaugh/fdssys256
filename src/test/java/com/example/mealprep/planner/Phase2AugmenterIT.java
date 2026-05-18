package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
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
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2AugmentationResponse;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Integration test for {@link Phase2Augmenter} against a real Spring context + Postgres
 * (Testcontainers). Uses one {@code @SpringBootTest} context (context startup is ~4 min on the CI
 * box — a second context would blow the window) with {@code @MockBean AiService} so the
 * happy/invalid/unavailable paths are driven in-process, while the <b>real</b> {@code
 * HardConstraintFilterService} verifies augmentations (no {@code @MockBean} on the filter, per
 * ticket planner-01h DoD).
 *
 * <p>The unit-level {@code Phase2AugmenterImplTest} additionally exercises the parser/verifier
 * wiring with mutation-strong coverage; this IT proves the bean graph wires up and the real
 * constraint filter discards a hallucinated recipe end-to-end.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class Phase2AugmenterIT {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  @Autowired private Phase2Augmenter augmenter;
  @MockBean private AiService aiService;

  private static CandidatePlan chosenPlan() {
    return new CandidatePlan(UUID.randomUUID(), WEEK_START, List.of(), null);
  }

  private static CandidatePlanRollupDto rollup() {
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

  /**
   * @return {@code [ctx, slotId, recipeId]} with one pooled snack recipe on a shared slot.
   */
  private static Object[] env() {
    UUID slotId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe =
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
    PlanCompositionContext ctx =
        new PlanCompositionContext(
            UUID.randomUUID(),
            WEEK_START,
            List.of(slot),
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(List.of(recipe), Instant.parse("2026-01-01T00:00:00Z")),
            List.of(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Map.of());
    return new Object[] {ctx, slotId, recipeId};
  }

  private static AugmentationProposal addSnack(UUID slot, UUID recipe) {
    return new AugmentationProposal(
        "ADD_SNACK", slot, recipe, 1, null, null, null, null, "fill protein gap");
  }

  @SuppressWarnings("unchecked")
  private void canned(Phase2AugmentationResponse response) {
    when(aiService.execute(any(AiTask.class))).thenReturn(response);
  }

  @Test
  void happyPath_realFilterAcceptsVerifiedAugmentation() {
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    UUID slotId = (UUID) e[1];
    UUID recipeId = (UUID) e[2];
    canned(new Phase2AugmentationResponse(List.of(addSnack(slotId, recipeId)), List.of()));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    // No hard-constraints aggregate row for the eater -> real filter returns passes=true.
    assertThat(result.applied()).hasSize(1);
    assertThat(result.discardedByVerifier()).isEmpty();
    assertThat(result.emittedDirectives())
        .as("RefineDirectiveDto cross-module deferral — always empty in 01h")
        .isEmpty();
  }

  @Test
  void hallucinatedRecipe_discardedByRealHardConstraintFilterPath() {
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    UUID slotId = (UUID) e[1];
    canned(
        new Phase2AugmentationResponse(
            List.of(addSnack(slotId, UUID.randomUUID())), List.of())); // not in pool

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.discardedByVerifier()).hasSize(1);
  }

  @Test
  void aiUnavailable_skipAndFlag_emptyResult() {
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiUnavailableException("monthly cost cap reached"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.discardedByVerifier()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void transientFailureExhausted_surfacesAsAiUnavailable_emptyResult() {
    // The merged AI module surfaces retries-exhausted transient failures as
    // AiUnavailableException (no separate TransientAiFailureException type) — same degrade path.
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiUnavailableException("retries exhausted on transient 5xx"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
  }

  @Test
  void invalidResponse_degradesToEmpty_doesNotBrickComposition() {
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiInvalidResponseException("unparseable payload"));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.applied()).isEmpty();
    assertThat(result.emittedDirectives()).isEmpty();
  }

  @Test
  void refineDirectivesAlwaysEmpty_crossClasspathDeferral() {
    Object[] e = env();
    PlanCompositionContext ctx = (PlanCompositionContext) e[0];
    UUID slotId = (UUID) e[1];
    canned(
        new Phase2AugmentationResponse(
            List.of(),
            List.of(
                PlanTestData.refineDirectiveProposal(slotId, "butter", "ghee"),
                PlanTestData.refineDirectiveProposal(slotId, "rice", "quinoa"))));

    AugmentationResult result = augmenter.augment(chosenPlan(), rollup(), ctx, UUID.randomUUID());

    assertThat(result.emittedDirectives()).isEmpty();
  }
}
