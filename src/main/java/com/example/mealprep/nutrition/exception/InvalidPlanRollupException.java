package com.example.mealprep.nutrition.exception;

/**
 * Thrown by the floor-gate REST seam (and the service-layer validation step) when a {@code
 * CandidatePlanRollupDto} fails its semantic constraints: {@code endDate < startDate}, or any
 * {@code perDay[i].date} falling outside {@code [startDate, endDate]} inclusive. Mapped to HTTP 400
 * by {@code NutritionExceptionHandler}.
 */
public class InvalidPlanRollupException extends NutritionException {

  public InvalidPlanRollupException(String detail) {
    super(detail);
  }
}
