package com.example.mealprep.nutrition.exception;

/**
 * Thrown when a range query violates the [from, to] semantics: {@code from > to}, or the range
 * spans more than {@code 35} days. Mapped to HTTP 400 by {@code NutritionExceptionHandler}.
 */
public class InvalidIntakeRangeException extends NutritionException {

  public InvalidIntakeRangeException(String detail) {
    super(detail);
  }
}
