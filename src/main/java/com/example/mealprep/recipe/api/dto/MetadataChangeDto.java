package com.example.mealprep.recipe.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry in {@code RecipeDiffDto.metadataChanges[]}. {@code field} is the metadata scalar's Java
 * property name (e.g. {@code "totalTimeMins"}); {@code from}/{@code to} carry the prior / new value
 * as {@link JsonNode}.
 */
public record MetadataChangeDto(ChangeAction action, String field, JsonNode from, JsonNode to) {}
