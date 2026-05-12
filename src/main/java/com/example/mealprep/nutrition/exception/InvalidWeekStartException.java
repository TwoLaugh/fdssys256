package com.example.mealprep.nutrition.exception;

import java.time.DayOfWeek;

/**
 * Thrown by the weekly aggregate endpoint (and the service-layer validation step) when the {@code
 * weekStart} path variable is not a Monday. Mapped to HTTP 400 by {@code
 * NutritionExceptionHandler}.
 */
public class InvalidWeekStartException extends NutritionException {

  public InvalidWeekStartException(DayOfWeek actualDay) {
    super(
        "weekStart must be a Monday (ISO-8601 day-of-week 1); got "
            + (actualDay == null ? "null" : actualDay.name()));
  }
}
