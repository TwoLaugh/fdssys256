package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;

/**
 * Returned by the pipeline (internally, wrapped in {@code IngredientMappingResult.Unmapped}) when
 * neither USDA nor OFF can resolve a term. Not exposed as a 200 response — the controller maps
 * unmapped results to a 404 ProblemDetail.
 */
public record UnmappedIngredientDto(String name, String reason, BigDecimal confidence) {}
