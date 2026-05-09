package com.example.mealprep.recipe.api.dto;

import java.util.List;

/** Read shape of metadata for a recipe version. */
public record RecipeMetadataDto(
    int servings,
    int prepTimeMins,
    int cookTimeMins,
    int totalTimeMins,
    List<String> equipmentRequired,
    Integer fridgeDays,
    Integer freezerWeeks,
    boolean packable,
    String cuisine,
    List<String> mealTypes) {}
