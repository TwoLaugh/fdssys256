package com.example.mealprep.planner.api.dto;

import java.util.UUID;

/**
 * The LLM's raw Phase-2 augmentation output shape — untyped and JSON-friendly. Per ticket
 * planner-01h §"Sealed {@code Augmentation} hierarchy" and lld/planner.md §{@code
 * Phase2AugmentationResponse} (lines 908-910).
 *
 * <p>Field relevance depends on {@code type}:
 *
 * <ul>
 *   <li>{@code "ADD_SNACK"} — {@code targetSlotId}, {@code newRecipeId}, {@code servings}
 *   <li>{@code "INGREDIENT_SWAP"} — {@code targetSlotId}, {@code fromIngredientKey}, {@code
 *       toIngredientKey}
 *   <li>{@code "REPAIR"} — {@code targetSlotId} (nullable), {@code issue}, {@code resolution}
 * </ul>
 *
 * {@code reasoning} is common to all types. Converted to the typed sealed {@code Augmentation}
 * hierarchy by {@code AugmentationParser}; malformed proposals are dropped (parser returns {@code
 * null}).
 */
public record AugmentationProposal(
    String type,
    UUID targetSlotId,
    UUID newRecipeId,
    Integer servings,
    String fromIngredientKey,
    String toIngredientKey,
    String issue,
    String resolution,
    String reasoning) {}
