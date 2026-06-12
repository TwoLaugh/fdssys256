package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.hibernate.validator.constraints.URL;

/**
 * Request body for {@code POST /api/v1/recipes/imports/url}. Server resolves {@code userId} via
 * {@code CurrentUserResolver}; {@code catalogue} defaults to {@link Catalogue#USER} when omitted.
 *
 * <p>{@code ignoreDuplicateOfRecipeId} (recipe-import-dedup-consistency ticket): the one-shot path
 * now runs the same dedup gate as preview→confirm, so it 422s {@code recipe-import-duplicate} on a
 * library collision and honours the same named override — persist despite a collision with exactly
 * this recipe; a collision with any other recipe still 422s.
 */
public record ImportRecipeFromUrlRequest(
    @NotBlank @URL @Size(max = 2048) String url,
    Catalogue catalogue,
    UUID ignoreDuplicateOfRecipeId) {

  /**
   * Pre-override 2-arg constructor — defaults {@code ignoreDuplicateOfRecipeId} to {@code null}
   * (additive-DTO convention). Existing call sites compile unchanged.
   */
  public ImportRecipeFromUrlRequest(String url, Catalogue catalogue) {
    this(url, catalogue, null);
  }
}
