package com.example.mealprep.recipe.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry in {@code RecipeDiffDto.tagChanges[]}. {@code dimension} is the {@link
 * com.example.mealprep.recipe.domain.entity.RecipeTags} field name (e.g. {@code "flavourProfile"});
 * {@code from}/{@code to} carry the prior / new value (scalar or list).
 */
public record TagChangeDto(ChangeAction action, String dimension, JsonNode from, JsonNode to) {}
