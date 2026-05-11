package com.example.mealprep.household.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Lightweight stub shape for the household merge surface. Preference-01c will own the canonical
 * record; until then this record lets the merge interface validate against a real type rather than
 * {@code Object}. See LLD divergence note in 01e's ticket.
 *
 * @param ingredientLikes ingredientMappingKey to like-score in {@code [-1, 1]}
 * @param cuisineLikes cuisine name to like-score in {@code [-1, 1]}
 * @param avoidList ingredientMappingKey strings (NOT allergens — those are hard-constraints)
 */
public record TasteProfileDocument(
    Map<String, BigDecimal> ingredientLikes,
    Map<String, BigDecimal> cuisineLikes,
    List<String> avoidList) {}
