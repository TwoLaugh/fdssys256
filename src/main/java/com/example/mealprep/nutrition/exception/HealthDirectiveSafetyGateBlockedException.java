package com.example.mealprep.nutrition.exception;

import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import java.util.List;
import java.util.UUID;

/**
 * Thrown when {@code DirectiveSafetyGate} returns {@code BLOCKED}. Carries the findings list so the
 * handler can surface it as a ProblemDetail extension. Mapped to HTTP 422 by {@code
 * NutritionExceptionHandler}; the directive stays in {@code PENDING_REVIEW} so the user can modify
 * and retry (LLD line 1013).
 */
public class HealthDirectiveSafetyGateBlockedException extends NutritionException {

  private final UUID directiveId;
  private final List<SafetyFindingDto> findings;

  public HealthDirectiveSafetyGateBlockedException(
      UUID directiveId, List<SafetyFindingDto> findings) {
    super("Health directive blocked by safety gate: " + directiveId);
    this.directiveId = directiveId;
    this.findings = List.copyOf(findings);
  }

  public UUID directiveId() {
    return directiveId;
  }

  public List<SafetyFindingDto> findings() {
    return findings;
  }
}
