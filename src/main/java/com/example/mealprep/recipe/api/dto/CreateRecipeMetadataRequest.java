package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.validation.ValidRecipeMetadata;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Metadata block of a {@code CreateRecipeRequest}. {@code @ValidRecipeMetadata} class-level
 * validator asserts {@code totalTimeMins ≈ prepTimeMins + cookTimeMins}.
 */
@ValidRecipeMetadata
public record CreateRecipeMetadataRequest(
    @Min(1) int servings,
    @Min(0) int prepTimeMins,
    @Min(0) int cookTimeMins,
    @Min(0) int totalTimeMins,
    List<@Size(max = 64) String> equipmentRequired,
    Integer fridgeDays,
    Integer freezerWeeks,
    Boolean packable,
    @Size(max = 64) String cuisine,
    List<@Size(max = 32) String> mealTypes) {}
