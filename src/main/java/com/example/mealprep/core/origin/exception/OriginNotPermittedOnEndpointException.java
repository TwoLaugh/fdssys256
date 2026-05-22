package com.example.mealprep.core.origin.exception;

/**
 * Thrown by {@link com.example.mealprep.core.origin.OriginFilter} when a non-USER origin is sent to
 * a controller handler that lacks the {@link com.example.mealprep.core.origin.OriginAware}
 * annotation. Defence-in-depth so a contributor adding a new mutation endpoint cannot accidentally
 * accept system-driven traffic.
 *
 * <p>Mapped to HTTP 403 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class OriginNotPermittedOnEndpointException extends RuntimeException {

  public OriginNotPermittedOnEndpointException(String origin, String path) {
    super("Origin " + origin + " is not permitted on endpoint " + path + ".");
  }
}
