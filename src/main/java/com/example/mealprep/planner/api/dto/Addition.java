package com.example.mealprep.planner.api.dto;

import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One in-meal addition bolted onto a slot's main recipe to close a day's residual calories + short
 * micronutrients after portion scaling (Phase 2 — {@code
 * design/nutrition/portion-scaling-and-additions.md}). Additions are a per-slot rider, NOT a second
 * scheduled recipe (the DB enforces one scheduled recipe per slot), so they ride on {@link
 * SlotAssignment} in-memory and persist as a JSONB list on {@code ScheduledRecipe}.
 *
 * <p>Each addition carries its OWN nutrition ({@code nutrition}) rather than being looked up from a
 * recipe, so {@code DailyMacroAggregator} can sum it directly — an {@link AdditionKind#INGREDIENT}
 * is not recipe-backed, and even a {@link AdditionKind#SIDE_RECIPE} copies the side's per-serving
 * figures here so the aggregator path is uniform. The reused {@link NutritionPerServingDto} carries
 * the macros, micros, and per-micro provenance ({@code microSources}/{@code microConfidence}) so an
 * ingredient's USDA-derived values flow through the coverage panel's source-blend already built.
 *
 * @param kind ingredient vs side recipe
 * @param name human label for the slot ("½ avocado", "side salad")
 * @param ingredientMappingKey normalised ingredient key (INGREDIENT only) — allergy check + grocery
 * @param recipeId the side recipe (SIDE_RECIPE only)
 * @param quantity amount in {@code unit} ("0.5" avocado, "1" tbsp)
 * @param unit the unit the quantity is expressed in ("tbsp", "cup", "whole", "g")
 * @param grams resolved grams (for grocery weight + sanity bounds); null if not weight-resolvable
 * @param nutrition this addition's own per-portion nutrition (macros + micros + provenance)
 * @param reasoning the pairing note ("½ avocado on the taco salad") — deterministic or LLM-written
 */
public record Addition(
    AdditionKind kind,
    String name,
    String ingredientMappingKey,
    UUID recipeId,
    BigDecimal quantity,
    String unit,
    BigDecimal grams,
    NutritionPerServingDto nutrition,
    String reasoning) {}
