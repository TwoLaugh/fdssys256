package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;

/**
 * Library list/search criteria for {@code GET /api/v1/recipes} — the shipped subset of the LLD's
 * {@code RecipeSearchCriteriaDto} (lld/recipe.md §Search); diet / meal-type / equipment / protein
 * facets are deferred to the v1.1 filter drawer.
 *
 * <p>Semantics (pinned by the recipe-list-search ticket):
 *
 * <ul>
 *   <li>{@code catalogue} — {@code null} means both: the caller's own {@code USER} rows plus all
 *       shared {@code SYSTEM} rows. {@code USER} restricts to the caller's private library; {@code
 *       SYSTEM} to the shared pool.
 *   <li>{@code namePattern} — case-insensitive substring match on {@code name}; {@code null} means
 *       no name constraint.
 *   <li>{@code cuisine} — exact match on the current version's {@code metadata.cuisine}.
 *   <li>{@code maxTotalTimeMins} — {@code metadata.totalTimeMins <= value}.
 *   <li>{@code minDataQuality} — ordinal floor, not equality (see {@link
 *       com.example.mealprep.recipe.domain.service.internal.DataQualityGate}).
 *   <li>{@code includeArchived} — {@code false} (the default) restricts to {@code archivedAt IS
 *       NULL}.
 * </ul>
 *
 * <p>Soft-deleted rows are excluded unconditionally; the criteria cannot reach them.
 */
public record RecipeSearchCriteriaDto(
    Catalogue catalogue,
    String namePattern,
    String cuisine,
    Integer maxTotalTimeMins,
    DataQuality minDataQuality,
    boolean includeArchived) {}
