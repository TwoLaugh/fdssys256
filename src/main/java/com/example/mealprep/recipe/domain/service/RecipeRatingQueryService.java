package com.example.mealprep.recipe.domain.service;

import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read contract for multi-dimensional recipe ratings (recipe-02b). */
public interface RecipeRatingQueryService {

  Optional<RecipeRatingDto> getById(UUID ratingId);

  Optional<RecipeRatingDto> getByVersionAndUser(UUID versionId, UUID userId);

  Page<RecipeRatingDto> listByVersion(UUID versionId, Pageable p);

  Page<RecipeRatingDto> listByRecipe(UUID recipeId, Pageable p);

  Page<RecipeRatingDto> listByUser(UUID userId, Pageable p);

  RecipeRatingSummaryDto getSummaryByVersion(UUID versionId);

  RecipeRatingSummaryDto getSummaryByRecipe(UUID recipeId);
}
