package com.example.mealprep.recipe.domain.entity;

/**
 * JSONB-inner record for the {@code method_overlay} column on {@code recipe_substitutions}. {@code
 * step} is 1-indexed and matches {@code recipe_method_steps.step_number}.
 */
public record MethodOverlayLine(int step, String instruction) {}
