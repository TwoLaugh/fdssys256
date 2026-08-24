package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a {@code RecipeImport}. The {@code sourcePayload} JSONB is populated only on the
 * dedicated provenance endpoint ({@code GET /api/v1/recipes/{recipeId}/import-provenance}); it is
 * fetched LAZY at the entity layer to keep hot reads light.
 *
 * <p>{@code sourceKey} (G10, additive): the discovery/graph source key already persisted on the row
 * (e.g. {@code "bbcgoodfood-sitemap"}, {@code "graph:<campaign-id>"}) — exposed so the detail page
 * can show the campaign id without a schema migration. {@code null} for 01b URL imports.
 */
public record RecipeImportDto(
    UUID id,
    UUID recipeId,
    ImportSource sourceType,
    String sourceUrl,
    JsonNode sourcePayload,
    String extractionMethod,
    String sourceKey,
    UUID duplicateOfRecipeId,
    Instant importedAt,
    UUID importedByUserId) {}
