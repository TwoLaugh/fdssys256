package com.example.mealprep.nutrition.exception;

import java.util.UUID;

/**
 * Thrown when the {@code RecipeNutritionWriter} SPI implementation rejects the computed nutrition
 * payload (e.g. recipe-side validation: version immutable, branch mismatch). Mapped to HTTP 422 by
 * {@code NutritionExceptionHandler}.
 *
 * <p>The Noop fallback does NOT throw this — it logs WARN and returns. This exception fires only
 * when a real impl (recipe-01f) is wired and refuses the write.
 */
public class RecipeNutritionWriteFailedException extends NutritionException {

  private final UUID versionId;

  public RecipeNutritionWriteFailedException(UUID versionId, String message) {
    super("Recipe nutrition write failed for version " + versionId + ": " + message);
    this.versionId = versionId;
  }

  public RecipeNutritionWriteFailedException(UUID versionId, String message, Throwable cause) {
    super("Recipe nutrition write failed for version " + versionId + ": " + message, cause);
    this.versionId = versionId;
  }

  public UUID versionId() {
    return versionId;
  }
}
