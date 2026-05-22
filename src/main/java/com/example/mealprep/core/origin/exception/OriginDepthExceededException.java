package com.example.mealprep.core.origin.exception;

/**
 * Thrown by {@link com.example.mealprep.core.origin.OriginFilter} when {@code X-Origin-Depth} on an
 * inbound request exceeds the configured maximum (3 — see {@code design/origin-tracking-pattern.md}
 * §Recursion guard).
 *
 * <p>This is the recursion guard — left unchecked, AI-feedback → endpoint → event → AI-feedback
 * loops would be unbounded.
 *
 * <p>Mapped to HTTP 422 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class OriginDepthExceededException extends RuntimeException {

  private final int depth;

  public OriginDepthExceededException(int depth) {
    super("X-Origin-Depth=" + depth + " exceeds the configured maximum of 3.");
    this.depth = depth;
  }

  public int getDepth() {
    return depth;
  }
}
