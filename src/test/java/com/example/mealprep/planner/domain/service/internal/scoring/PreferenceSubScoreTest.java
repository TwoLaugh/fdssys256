package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.preference.domain.service.TasteSimilarityQueryService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PreferenceSubScore} — verifies the LOCKED per-recipe cosine formula
 * (lld/planner.md §PreferenceSubScore): {@code (cosine_similarity(recipe.embedding, taste_vector) +
 * 1) / 2}, averaged over slots, with a {@code 0.5} neutral fallback whenever a recipe embedding or
 * taste vector is missing. Uses fixture vectors only — no live embedding call.
 */
class PreferenceSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private static final BigDecimal NEUTRAL = new BigDecimal("0.5");

  private final TasteSimilarityQueryService tasteSimilarity =
      mock(TasteSimilarityQueryService.class);
  private final PreferenceModule preferenceModule = mock(PreferenceModule.class);
  private final PreferenceSubScore calc = new PreferenceSubScore(preferenceModule);

  PreferenceSubScoreTest() {
    lenient().when(preferenceModule.tasteSimilarity()).thenReturn(tasteSimilarity);
  }

  @Test
  void name_is_preference() {
    assertThat(calc.name()).isEqualTo("preference");
  }

  @Test
  void returns_neutral_for_empty_plan() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(NEUTRAL);
  }

  // ---- LOCKED formula: cosine mapped (cos + 1) / 2 -------------------------------------------

  @Test
  void identical_vectors_score_one() {
    float[] vector = {0.6f, 0.8f};
    assertThat(scoreSingleSlot(vector, vector)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void orthogonal_vectors_score_neutral_half() {
    // cos = 0 → (0 + 1) / 2 = 0.5
    assertThat(scoreSingleSlot(new float[] {1f, 0f}, new float[] {0f, 1f}))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void opposite_vectors_score_zero() {
    // cos = -1 → (-1 + 1) / 2 = 0
    assertThat(scoreSingleSlot(new float[] {1f, 0f}, new float[] {-1f, 0f}))
        .isEqualByComparingTo(new BigDecimal("0.000000"));
  }

  @Test
  void parallel_but_unnormalised_vectors_score_one() {
    // magnitude is divided out by the normalisation, so 2× the same direction is still cos = 1
    assertThat(scoreSingleSlot(new float[] {1f, 1f}, new float[] {3f, 3f}))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  // ---- Neutral fallbacks --------------------------------------------------------------------

  @Test
  void recipe_without_embedding_scores_neutral() {
    UUID recipeId = UUID.randomUUID();
    UUID eater = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    when(tasteSimilarity.getTasteVector(eater)).thenReturn(Optional.of(new float[] {1f, 0f}));

    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, null);
    PlanCompositionContext ctx =
        PlanTestData.minimalContext(
            List.of(PlanTestData.skeletonWithEaters(slotId, WEEK, 0, List.of(eater))),
            List.of(recipe));
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK, List.of(PlanTestData.assignment(slotId, recipeId, WEEK, 0, 1))),
                ctx))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void user_without_taste_vector_scores_neutral() {
    UUID recipeId = UUID.randomUUID();
    UUID eater = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    when(tasteSimilarity.getTasteVector(eater)).thenReturn(Optional.empty());

    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, new float[] {1f, 0f});
    PlanCompositionContext ctx =
        PlanTestData.minimalContext(
            List.of(PlanTestData.skeletonWithEaters(slotId, WEEK, 0, List.of(eater))),
            List.of(recipe));
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK, List.of(PlanTestData.assignment(slotId, recipeId, WEEK, 0, 1))),
                ctx))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void slot_with_no_matching_skeleton_scores_neutral() {
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, new float[] {1f, 0f});
    // No skeletons → no eaters resolvable → neutral.
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK,
                    List.of(PlanTestData.assignment(UUID.randomUUID(), recipeId, WEEK, 0, 1))),
                ctx))
        .isEqualByComparingTo(NEUTRAL);
  }

  // ---- Shared-slot merged household vector (element-wise mean of eater vectors) ---------------

  @Test
  void shared_slot_uses_elementwise_mean_of_eater_vectors() {
    UUID recipeId = UUID.randomUUID();
    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    // Mean of (1,0) and (0,1) is (0.5,0.5); recipe vector (1,1) is parallel to it → cos 1.
    when(tasteSimilarity.getTasteVector(eaterA)).thenReturn(Optional.of(new float[] {1f, 0f}));
    when(tasteSimilarity.getTasteVector(eaterB)).thenReturn(Optional.of(new float[] {0f, 1f}));

    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, new float[] {1f, 1f});
    PlanCompositionContext ctx =
        PlanTestData.minimalContext(
            List.of(PlanTestData.skeletonWithEaters(slotId, WEEK, 0, List.of(eaterA, eaterB))),
            List.of(recipe));
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK, List.of(PlanTestData.assignment(slotId, recipeId, WEEK, 0, 2))),
                ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void shared_slot_skips_eaters_without_a_vector() {
    UUID recipeId = UUID.randomUUID();
    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    // Only eaterA has a vector — the mean collapses to eaterA's (1,0); recipe (1,0) → cos 1.
    when(tasteSimilarity.getTasteVector(eaterA)).thenReturn(Optional.of(new float[] {1f, 0f}));
    when(tasteSimilarity.getTasteVector(eaterB)).thenReturn(Optional.empty());

    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, new float[] {1f, 0f});
    PlanCompositionContext ctx =
        PlanTestData.minimalContext(
            List.of(PlanTestData.skeletonWithEaters(slotId, WEEK, 0, List.of(eaterA, eaterB))),
            List.of(recipe));
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK, List.of(PlanTestData.assignment(slotId, recipeId, WEEK, 0, 2))),
                ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  // ---- Mean over slots ----------------------------------------------------------------------

  @Test
  void plan_level_score_is_mean_over_slots() {
    UUID matchId = UUID.randomUUID();
    UUID orthoId = UUID.randomUUID();
    UUID eater = UUID.randomUUID();
    UUID slot1 = UUID.randomUUID();
    UUID slot2 = UUID.randomUUID();
    when(tasteSimilarity.getTasteVector(eater)).thenReturn(Optional.of(new float[] {1f, 0f}));

    RecipeDto match = PlanTestData.scoredRecipeWithEmbedding(matchId, new float[] {1f, 0f}); // 1.0
    RecipeDto ortho = PlanTestData.scoredRecipeWithEmbedding(orthoId, new float[] {0f, 1f}); // 0.5
    PlanCompositionContext ctx =
        PlanTestData.minimalContext(
            List.of(
                PlanTestData.skeletonWithEaters(slot1, WEEK, 0, List.of(eater)),
                PlanTestData.skeletonWithEaters(slot2, WEEK, 1, List.of(eater))),
            List.of(match, ortho));
    // mean(1.0, 0.5) = 0.75
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(
                    WEEK,
                    List.of(
                        PlanTestData.assignment(slot1, matchId, WEEK, 0, 1),
                        PlanTestData.assignment(slot2, orthoId, WEEK, 1, 1))),
                ctx))
        .isEqualByComparingTo(new BigDecimal("0.75"));
  }

  // ---- Static per-recipe edge cases ---------------------------------------------------------

  @Test
  void perRecipeScore_neutral_when_either_vector_null() {
    assertThat(PreferenceSubScore.perRecipeScore(null, new float[] {1f}))
        .isEqualByComparingTo(NEUTRAL);
    assertThat(PreferenceSubScore.perRecipeScore(new float[] {1f}, null))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void perRecipeScore_neutral_on_dimension_mismatch() {
    assertThat(PreferenceSubScore.perRecipeScore(new float[] {1f, 0f}, new float[] {1f}))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void perRecipeScore_neutral_on_zero_norm_vector() {
    assertThat(PreferenceSubScore.perRecipeScore(new float[] {0f, 0f}, new float[] {1f, 1f}))
        .isEqualByComparingTo(NEUTRAL);
  }

  @Test
  void perRecipeScore_neutral_on_empty_vector() {
    assertThat(PreferenceSubScore.perRecipeScore(new float[] {}, new float[] {}))
        .isEqualByComparingTo(NEUTRAL);
  }

  private BigDecimal scoreSingleSlot(float[] recipeVector, float[] tasteVector) {
    UUID recipeId = UUID.randomUUID();
    UUID eater = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    when(tasteSimilarity.getTasteVector(eater)).thenReturn(Optional.of(tasteVector));
    RecipeDto recipe = PlanTestData.scoredRecipeWithEmbedding(recipeId, recipeVector);
    MealSlotSkeleton skeleton = PlanTestData.skeletonWithEaters(slotId, WEEK, 0, List.of(eater));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(skeleton), List.of(recipe));
    SlotAssignment assignment = PlanTestData.assignment(slotId, recipeId, WEEK, 0, 1);
    return calc.compute(PlanTestData.candidatePlan(WEEK, List.of(assignment)), ctx);
  }
}
