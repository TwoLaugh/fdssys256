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
 *       {@code float[] embedding} field — the pgvector column is not surfaced on the read DTO.
 *   <li>{@code SoftPreferenceBundleDto.tasteProfile()} ({@code TasteProfileDocument}) carries
 *       {@code ingredientLikes / cuisineLikes / avoidList} maps, NOT a dense {@code tasteVector}
 *       (preference-01g's embedding ticket is not merged on this branch).
 * </ul>
 *
 * <p>Per ticket items 11 / 59 ("If preference doesn't expose tasteVector yet, 01e returns 0.5
 * universally with a TODO"), this calculator returns the {@code 0.5} neutral fallback for every
 * plan. The cosine-similarity machinery is intentionally NOT stubbed in — wiring it against
 * placeholder vectors would produce misleading non-neutral scores. The composite weight is still
 * applied, so preference contributes a constant {@code 0.5 × w_preference} until the embedding
 * contracts ship.
 *
 * <p><b>TODO(user / planner-01j wiring)</b>: when {@code RecipeVersionDto.embedding} and {@code
 * SoftPreferenceBundleDto.tasteVector()} (or merged-household equivalent) land, implement the
 * LOCKED per-recipe cosine formula: {@code dot(a,b) / (norm(a) × norm(b))}, NaN-guarded to {@code
 * 0.5} on a zero-norm vector, mapped {@code [-1,1] → [0,1]}, averaged across slots; shared slots
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
