package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRequest;

/**
 * G05 (graph integration): idempotent, first-writer-wins seeding of {@code
 * nutrition_ingredient_mapping} from the spike-canon seed artifact. Lives behind the nutrition
 * module boundary because the repository is module-private ({@code NutritionBoundaryTest}).
 *
 * <p>Semantics per row: absent → insert (confidence 1.000, {@code needsReview=false}, {@code
 * basisNote} stamped); present and deep-equal on (source, externalId, nutritionPer100g) →
 * idempotent skip; present and different → COLLISION, never overwritten, whole run reports {@code
 * FAILED}. The seed MUST run before any lazy population touches spike-canon keys — a collision is
 * the detective evidence that ordering was violated.
 */
public interface IngredientMappingSeedService {

  IngredientMappingSeedReport seed(IngredientMappingSeedRequest request);
}
