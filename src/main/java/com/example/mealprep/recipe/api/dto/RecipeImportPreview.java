package com.example.mealprep.recipe.api.dto;

import java.util.List;

/**
 * Result of an import <b>preview</b> (recipe-3 / LLD §Flow 2 — Paprika-style preview-then-confirm).
 * Extraction has run (via the shared {@code RecipeExtractionService}) but <b>nothing was
 * persisted</b>. The frontend renders {@code parsedRecipe} as an editable form; the user reviews /
 * edits it and POSTs it back to {@code /imports/confirm} together with {@code previewToken}.
 *
 * @param previewToken opaque short-lived reference the confirm call echoes back. v1 keeps the flow
 *     stateless — the confirm body carries the authoritative (possibly-edited) recipe, so the token
 *     is correlation/telemetry only and is not required to reconstruct the candidate. (The LLD's
 *     signed-cache token is reserved for when server-side extraction caching lands.)
 * @param parsedRecipe the extracted candidate in editable {@link CreateRecipeRequest} shape
 * @param sourceUrl the URL the candidate was extracted from (provenance)
 * @param extractionMethod the winning extraction layer label ({@code json_ld} / {@code microdata} /
 *     {@code common_selectors} / …)
 * @param validationWarnings non-fatal notes about the extraction (e.g. fields defaulted); empty
 *     when the candidate is clean
 * @param dedupCandidate a near-duplicate already in the caller's library, or {@code null} when none
 *     crosses the dedup threshold — lets the UI warn before the user wastes time editing
 */
public record RecipeImportPreview(
    String previewToken,
    CreateRecipeRequest parsedRecipe,
    String sourceUrl,
    String extractionMethod,
    List<String> validationWarnings,
    DedupCandidateDto dedupCandidate) {}
