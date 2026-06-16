package com.example.mealprep.planner.domain.service.internal.additions;

import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * A curated in-meal addition candidate (Phase 2 — {@code
 * design/nutrition/portion-scaling-and-additions.md}). The catalogue defines <i>which</i> whole
 * foods are sensible additions + their portion + which nutrients they are strong in; the actual
 * nutrition is USDA-derived (resolved live from the nutrition module's ingredient-mapping cache when
 * present, else from {@code per100g} here as a USDA-sourced fallback — see {@code
 * AdditionNutritionResolver}).
 *
 * @param ingredientKey normalised ingredient mapping key (allergy filter + grocery + USDA lookup)
 * @param displayName slot label ("½ avocado", "1 tbsp olive oil")
 * @param quantity portion amount in {@code unit}
 * @param unit portion unit ("tbsp", "cup", "whole", "handful")
 * @param grams resolved grams for that portion (drives nutrition scaling + grocery weight)
 * @param affinityMicros canonical micro keys this food is rich in (gap→food ranking)
 * @param fillsCalories true for calorie-dense top-ups (oils/nuts/seeds) — ranked for residual kcal
 * @param per100g USDA-sourced per-100g nutrition (fallback when the live mapping cache is empty)
 */
record AdditionCandidate(
    String ingredientKey,
    String displayName,
    BigDecimal quantity,
    String unit,
    BigDecimal grams,
    List<String> affinityMicros,
    boolean fillsCalories,
    NutritionPerServingDto per100g) {}
