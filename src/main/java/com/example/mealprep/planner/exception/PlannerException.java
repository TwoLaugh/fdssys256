package com.example.mealprep.planner.exception;

/**
 * Module-root exception for the planner module. Per-failure subclasses extend this so {@code
 * PlannerExceptionHandler} can map either the specific subtype or the root if a future subtype is
 * added without a handler.
 */
public class PlannerException extends RuntimeException {

  public PlannerException(String message) {
    super(message);
  }

  public PlannerException(String message, Throwable cause) {
    super(message, cause);
  }
}
