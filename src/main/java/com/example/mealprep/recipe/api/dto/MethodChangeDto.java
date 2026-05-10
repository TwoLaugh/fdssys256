package com.example.mealprep.recipe.api.dto;

/** One entry in {@code RecipeDiffDto.methodChanges[]}. Matched by {@code stepNumber}. */
public record MethodChangeDto(ChangeAction action, int step, String from, String to) {}
