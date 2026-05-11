package com.example.mealprep.nutrition.exception;

import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import java.util.UUID;

/**
 * Thrown by the inbound endpoint when a re-delivery collides with an existing row by {@code
 * (sourcePlatform, externalDirectiveId)}. Carries the existing row's id + status so the handler can
 * surface them as ProblemDetail extensions. Mapped to HTTP 409.
 */
public class DuplicateHealthDirectiveException extends NutritionException {

  private final UUID existingDirectiveId;
  private final DirectiveStatus existingStatus;

  public DuplicateHealthDirectiveException(
      UUID existingDirectiveId, DirectiveStatus existingStatus) {
    super("Duplicate health directive: existingId=" + existingDirectiveId);
    this.existingDirectiveId = existingDirectiveId;
    this.existingStatus = existingStatus;
  }

  public UUID existingDirectiveId() {
    return existingDirectiveId;
  }

  public DirectiveStatus existingStatus() {
    return existingStatus;
  }
}
