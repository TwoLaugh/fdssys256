package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.CreateRatingRequest;
import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.api.dto.UpdateRatingRequest;
import java.util.UUID;

/** Write contract for multi-dimensional recipe ratings (recipe-02b). */
public interface RecipeRatingUpdateService {

  /**
   * Record a new rating on the version named in the request, validating that the version belongs to
   * {@code recipeId}. Computes the aggregate, persists, and publishes {@code
   * RecipeRatingFiredEvent} after commit. 409 if the user already rated this version.
   */
  RecipeRatingDto create(UUID userId, UUID recipeId, CreateRatingRequest request);

  /**
   * Revise an existing rating the caller owns. Recomputes the aggregate, bumps the optimistic
   * version, and publishes {@code RecipeRatingFiredEvent} after commit.
   */
  RecipeRatingDto update(UUID userId, UUID recipeId, UUID ratingId, UpdateRatingRequest request);

  /** Delete a rating the caller owns. No event is published. */
  void delete(UUID userId, UUID recipeId, UUID ratingId);
}
