package com.example.mealprep.nutrition.exception;

import java.util.UUID;

/**
 * Thrown when a directive lookup yields no row, OR when the lookup matches but the caller is not
 * the directive's target user. We collapse both cases to 404 so we don't leak the existence of
 * other users' directives. Mapped to HTTP 404 by {@code NutritionExceptionHandler}.
 */
public class HealthDirectiveNotFoundException extends NutritionException {

  private final UUID directiveId;

  public HealthDirectiveNotFoundException(UUID directiveId) {
    super("Health directive not found: " + directiveId);
    this.directiveId = directiveId;
  }

  public UUID directiveId() {
    return directiveId;
  }
}
