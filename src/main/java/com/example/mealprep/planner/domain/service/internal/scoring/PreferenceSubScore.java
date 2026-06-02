package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Preference (taste-fit) sub-score. Algorithm LOCKED per LLD §PreferenceSubScore (lld/planner.md,
 * 2026-05-07):
 *
 * <pre>
 *   per_recipe_score(recipe, taste_vector):
 *     if recipe.embedding is null OR taste_vector is null: return 0.5   // neutral fallback
 *     cos = cosine_similarity(recipe.embedding, taste_vector)
 *     return (cos + 1) / 2                                              // map [-1, 1] -> [0, 1]
 *   PreferenceSubScore(plan):
 *     return mean(per_recipe_score(slot.recipe, taste_vector_for(slot.eaters)) for slot in slots)
 * </pre>
 *
 * <p><b>recipe-01i wiring — both halves now available.</b> The per-recipe vector is read from
 * {@code RecipeDto.currentVersionBody().embedding()} (surfaced from the pgvector column in
 * recipe-01i); the per-user taste vector is read from {@code
 * PreferenceModule#tasteSimilarity().getTasteVector(userId)} (preference-5). Both are nullable — a
 * recipe whose embedding has not yet landed, or a user whose taste vector is PENDING/FAILED, yields
 * the {@code 0.5} neutral per-slot score, so a plan full of unembedded recipes regresses toward
 * neutral rather than being pulled toward arbitrary recipes by missing data.
 *
 * <p><b>taste_vector_for(slot.eaters)</b>: per the LLD, per-person slots use that user's taste
 * vector and shared slots use the merged household taste vector (element-wise mean of the member
 * vectors). The cross-module read seam exposes only per-user vectors ({@code
 * getTasteVector(userId)}); the merged household vector is therefore computed here as the
 * element-wise mean of the available eater vectors for the slot. Eaters whose vector is absent are
 * skipped; if no eater has a vector the slot scores neutral. Slot eaters are resolved from {@code
 * ctx.slotSkeletons()} keyed by {@code slotId} (the beam search sets {@code SlotAssignment.slotId
 * == MealSlotSkeleton.slotId}); an assignment with no matching skeleton, or a skeleton with no
 * eaters, also scores neutral.
 *
 * <p>Pure function of {@code (plan, ctx)} plus the injected preference read surface (which is
 * itself a deterministic read of stored vectors) — no time, no randomness.
 */
@Component
class PreferenceSubScore implements SubScoreCalculator {

  /** Neutral fallback per LLD — emitted when a recipe or taste vector is missing. */
  static final BigDecimal NEUTRAL = new BigDecimal("0.500000");

  private final PreferenceModule preferenceModule;

  PreferenceSubScore(PreferenceModule preferenceModule) {
    this.preferenceModule = preferenceModule;
  }

  @Override
  public String name() {
    return "preference";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return NEUTRAL; // mean over zero slots is neutral (no taste signal)
    }

    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);
    Map<UUID, MealSlotSkeleton> bySlotId =
        ctx.slotSkeletons() == null
            ? Map.of()
            : ctx.slotSkeletons().stream()
                .collect(Collectors.toMap(MealSlotSkeleton::slotId, Function.identity()));

    BigDecimal sum = BigDecimal.ZERO;
    int counted = 0;
    for (SlotAssignment a : plan.assignments()) {
      float[] recipeVector = recipeVector(recipes, a.recipeId());
      float[] tasteVector = tasteVectorForSlot(bySlotId.get(a.slotId()));
      sum = sum.add(perRecipeScore(recipeVector, tasteVector));
      counted++;
    }
    return sum.divide(BigDecimal.valueOf(counted), 6, RoundingMode.HALF_UP);
  }

  /**
   * LOCKED per-recipe score: cosine similarity mapped {@code (cos + 1) / 2} into {@code [0, 1]}, or
   * {@code 0.5} neutral when either vector is missing, zero-length, dimension-mismatched, or
   * zero-norm (NaN-guarded).
   */
  static BigDecimal perRecipeScore(float[] recipeVector, float[] tasteVector) {
    if (recipeVector == null
        || tasteVector == null
        || recipeVector.length == 0
        || recipeVector.length != tasteVector.length) {
      return NEUTRAL;
    }
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < recipeVector.length; i++) {
      double a = recipeVector[i];
      double b = tasteVector[i];
      dot += a * b;
      normA += a * a;
      normB += b * b;
    }
    if (normA == 0.0 || normB == 0.0) {
      return NEUTRAL; // zero-norm vector → cosine undefined → neutral
    }
    double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
    // Clamp FP drift back into [-1, 1] before the [0, 1] remap.
    cos = Math.max(-1.0, Math.min(1.0, cos));
    double mapped = (cos + 1.0) / 2.0;
    return BigDecimal.valueOf(mapped).setScale(6, RoundingMode.HALF_UP);
  }

  /**
   * Resolve {@code taste_vector_for(slot.eaters)}: the element-wise mean of the available eater
   * taste vectors (a single eater yields that eater's vector; a shared slot yields the merged
   * household vector). Returns {@code null} when the skeleton is missing, has no eaters, or no
   * eater has an embedded vector — the caller maps {@code null} to the neutral per-slot score.
   */
  private float[] tasteVectorForSlot(MealSlotSkeleton skeleton) {
    if (skeleton == null || skeleton.eaters() == null || skeleton.eaters().isEmpty()) {
      return null;
    }
    double[] accumulator = null;
    int contributing = 0;
    for (UUID eater : skeleton.eaters()) {
      if (eater == null) {
        continue;
      }
      Optional<float[]> vector = preferenceModule.tasteSimilarity().getTasteVector(eater);
      if (vector.isEmpty()) {
        continue;
      }
      float[] v = vector.get();
      if (v.length == 0) {
        continue;
      }
      if (accumulator == null) {
        accumulator = new double[v.length];
      } else if (accumulator.length != v.length) {
        continue; // dimension mismatch between eaters — skip the outlier
      }
      for (int i = 0; i < v.length; i++) {
        accumulator[i] += v[i];
      }
      contributing++;
    }
    if (accumulator == null || contributing == 0) {
      return null;
    }
    float[] mean = new float[accumulator.length];
    for (int i = 0; i < accumulator.length; i++) {
      mean[i] = (float) (accumulator[i] / contributing);
    }
    return mean;
  }

  private static float[] recipeVector(Map<UUID, RecipeDto> recipes, UUID recipeId) {
    RecipeDto recipe = ScoringSupport.findRecipe(recipes, recipeId).orElse(null);
    if (recipe == null || recipe.currentVersionBody() == null) {
      return null;
    }
    return recipe.currentVersionBody().embedding();
  }
}
