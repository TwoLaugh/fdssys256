package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Complexity;
import java.util.List;

/** Read shape of the tag set for a recipe version. All fields nullable / possibly empty. */
public record RecipeTagsDto(
    String protein,
    String cookingMethod,
    Complexity complexity,
    List<String> flavourProfile,
    List<String> dietaryFlags) {}
