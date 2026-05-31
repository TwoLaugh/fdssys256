package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Preference (taste-fit) sub-score. Algorithm LOCKED per LLD §PreferenceSubScore (2026-05-07): mean
 * over slots of {@code (cosine_similarity(recipe.embedding, taste_vector) + 1) / 2}, with a {@code
 * 0.5} neutral fallback whenever an embedding or taste vector is missing.
 *
 * <p><b>01e codebase divergence — embeddings not yet exposed</b>: the LOCKED formula needs (a) a
 * per-recipe embedding and (b) a per-user/household taste vector. Neither exists on the current
 * cross-module contracts:
 *
 * <ul>
 *   <li>{@code RecipeVersionDto} (recipe-01h) carries an {@code embeddingStatus} string but NO
 *       {@code float[] embedding} field — the pgvector column is not surfaced on the read DTO. This
 *       is the remaining blocker (recipe-01i): the planner has no per-recipe vector to cosine
 *       against, so wiring similarity here would still need recipe-side exposure.
 *   <li><b>RESOLVED (preference-5):</b> the per-user taste vector + cosine surface now ship via
 *       {@code preference.domain.service.TasteSimilarityQueryService} (re-exported on {@code
 *       PreferenceModule#tasteSimilarity()}: {@code getTasteVector(userId)} / {@code
 *       cosineSimilarity(a,b)} mapped {@code (cos+1)/2}). The preference half of the LOCKED formula
 *       is therefore available; only the recipe-side embedding exposure remains.
 * </ul>
 *
 * <p>Per ticket items 11 / 59 ("If preference doesn't expose tasteVector yet, 01e returns 0.5
 * universally with a TODO"), this calculator returns the {@code 0.5} neutral fallback for every
 * plan. The cosine-similarity machinery is intentionally NOT stubbed in — wiring it against
 * placeholder vectors would produce misleading non-neutral scores. The composite weight is still
 * applied, so preference contributes a constant {@code 0.5 × w_preference} until the embedding
 * contracts ship.
 *
 * <p><b>TODO(recipe-01i wiring — preference side now ready)</b>: when {@code
 * RecipeVersionDto.embedding} (recipe-01i) surfaces the per-recipe vector into the candidate pool /
 * {@code PlanCompositionContext}, implement the LOCKED per-recipe cosine formula: {@code dot(a,b) /
 * (norm(a) × norm(b))}, NaN-guarded to {@code 0.5} on a zero-norm vector, mapped {@code [-1,1] →
 * [0,1]}, averaged across slots. The per-user/household taste vector half is already available via
 * {@code PreferenceModule#tasteSimilarity().getTasteVector(userId)} (preference-5); shared slots
 * use the merged household taste vector, per-person slots the eater's vector (element-wise mean for
 * multi-eater slots).
 */
@Component
class PreferenceSubScore implements SubScoreCalculator {

  /**
   * Neutral fallback per LLD — emitted universally until embedding contracts ship (see javadoc).
   */
  static final BigDecimal NEUTRAL = new BigDecimal("0.500000");

  @Override
  public String name() {
    return "preference";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    return NEUTRAL;
  }
}
