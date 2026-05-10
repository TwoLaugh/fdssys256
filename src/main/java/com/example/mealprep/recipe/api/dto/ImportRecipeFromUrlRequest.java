package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Request body for {@code POST /api/v1/recipes/imports/url}. Server resolves {@code userId} via
 * {@code CurrentUserResolver}; {@code catalogue} defaults to {@link Catalogue#USER} when omitted.
 */
public record ImportRecipeFromUrlRequest(
    @NotBlank @URL @Size(max = 2048) String url, Catalogue catalogue) {}
