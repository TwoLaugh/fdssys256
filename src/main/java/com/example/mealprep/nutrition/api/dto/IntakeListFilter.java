package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.util.UUID;

/**
 * Optional filter components for {@code GET /api/v1/nutrition/intake/search} (C-B-048). Each
 * component is optional ({@code null} = no filter); the service AND-composes the non-null parts.
 *
 * <ul>
 *   <li>{@code plannedRecipeId} — exact match against {@code IntakeSlot.plannedRecipeId}.
 *   <li>{@code mealSlot} — exact match against {@code IntakeSlot.mealSlot}.
 *   <li>{@code q} — case-insensitive substring match against {@code IntakeSlot.overrideFreeText}.
 *       Empty string is treated as no filter (not "match all empty notes"). Validated up to 200
 *       chars at the controller boundary. Cross-module recipe-name search is out of scope per the
 *       nutrition module boundary; see the audit doc for the rationale.
 * </ul>
 */
public record IntakeListFilter(UUID plannedRecipeId, MealSlot mealSlot, String q) {

  /** True iff {@code q} is non-null and not blank — i.e. should contribute to the query. */
  public boolean hasQuery() {
    return q != null && !q.isBlank();
  }
}
