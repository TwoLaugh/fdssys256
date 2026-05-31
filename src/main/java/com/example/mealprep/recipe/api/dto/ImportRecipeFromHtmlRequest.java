package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Request body for {@code POST /api/v1/recipes/imports/preview-html}. The frontend's in-app browser
 * supplies the page markup it already rendered plus the source URL, per LLD §Flow 2: used when (a)
 * the page is JS-rendered so a server fetch returns shell HTML, (b) the user is authenticated to a
 * paywalled source, or (c) exact-rendering fidelity is needed. Extraction runs against {@code
 * html}; {@code url} is carried for provenance + base-URI resolution.
 */
public record ImportRecipeFromHtmlRequest(
    @NotBlank @URL @Size(max = 2048) String url, @NotBlank @Size(max = 4_000_000) String html) {}
