package com.example.mealprep.recipe.api.dto;

/**
 * Response body for {@code POST /api/v1/recipes/{recipeId}/image}. The {@code imageUrl} is the
 * absolute server-relative URL the frontend should stick into an {@code <img src=...>} — NOT the
 * internal storage key. The stored key is module-internal and never leaks outside the recipe
 * package.
 *
 * <p>Introduced in recipe-02a.
 */
public record RecipeImageDto(String imageUrl, long sizeBytes, String contentType) {}
