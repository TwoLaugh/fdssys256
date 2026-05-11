package com.example.mealprep.nutrition.exception;

import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import java.util.UUID;

/**
 * Thrown when accept / reject targets a directive that has already left {@code PENDING_REVIEW}.
 * Mapped to HTTP 409 by {@code NutritionExceptionHandler}.
 */
public class HealthDirectiveAlreadyDecidedException extends NutritionException {

  private final UUID directiveId;
  private final DirectiveStatus currentStatus;

  public HealthDirectiveAlreadyDecidedException(UUID directiveId, DirectiveStatus currentStatus) {
    super("Health directive already decided: " + directiveId + " status=" + currentStatus);
    this.directiveId = directiveId;
    this.currentStatus = currentStatus;
  }

  public UUID directiveId() {
    return directiveId;
  }

  public DirectiveStatus currentStatus() {
    return currentStatus;
  }
}
