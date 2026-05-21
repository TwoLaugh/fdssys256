package com.example.mealprep.recipe.exception;

/**
 * Thrown when an uploaded recipe image's MIME type (browser-supplied or Tika-probed) is not in the
 * configured allow-list ({@code image/jpeg}, {@code image/png}, {@code image/webp} by default).
 * Mapped to HTTP 415 by {@code RecipeExceptionHandler}.
 *
 * <p>The Tika magic-byte probe is authoritative; the browser-supplied content-type is a hint only.
 *
 * <p>Introduced in recipe-02a alongside the image upload endpoint.
 */
public class UnsupportedRecipeImageMimeException extends RecipeException {

  private final String detectedMime;

  public UnsupportedRecipeImageMimeException(String detectedMime) {
    super(
        "Unsupported image MIME type: "
            + (detectedMime == null ? "<unknown>" : detectedMime)
            + ". Allowed: image/jpeg, image/png, image/webp.");
    this.detectedMime = detectedMime;
  }

  public String detectedMime() {
    return detectedMime;
  }
}
