package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.ViolationKind;
import java.util.UUID;

/**
 * One reason an ingredient key fails a user's hard constraints.
 *
 * @param userId which user the violation belongs to (populated for household / per-user variants;
 *     equal to the single user for the per-user check)
 * @param recipeId the originating recipe, when the call site provided one (e.g. {@code
 *     checkRecipe}); {@code null} for {@code check} / {@code checkForHousehold} where there's no
 *     recipe context
 * @param ingredientKey the ingredient that triggered the violation
 * @param kind which constraint family fired
 * @param constraintValue the stored constraint value that matched (e.g. the original allergen for
 *     ALLERGY-via-derivative cases; the substance for INTOLERANCE; the base diet for DIETARY_BASE)
 */
public record Violation(
    UUID userId, UUID recipeId, String ingredientKey, ViolationKind kind, String constraintValue) {}
