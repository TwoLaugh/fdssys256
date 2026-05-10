package com.example.mealprep.recipe.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry in {@code RecipeDiffDto.ingredientChanges[]}. The {@code from}/{@code to} snapshots are
 * inlined as {@link JsonNode} (not strongly typed) so the persisted JSONB round-trips without loss;
 * the OpenAPI shape is documented in {@code schemas/recipe.yaml}.
 *
 * <p>{@code fieldChanged} is set on {@code MODIFIED} only and identifies which scalar field differs
 * (one entry emitted per differing field per pair).
 */
public record IngredientChangeDto(
    ChangeAction action, JsonNode from, JsonNode to, String fieldChanged) {}
