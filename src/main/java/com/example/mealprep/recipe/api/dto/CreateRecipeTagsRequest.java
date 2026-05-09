package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Complexity;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Tag block of a {@code CreateRecipeRequest}. All fields optional in 01a. */
public record CreateRecipeTagsRequest(
    @Size(max = 64) String protein,
    @Size(max = 64) String cookingMethod,
    Complexity complexity,
    List<@Size(max = 32) String> flavourProfile,
    List<@Size(max = 32) String> dietaryFlags) {}
