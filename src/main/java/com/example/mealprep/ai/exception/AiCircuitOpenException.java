package com.example.mealprep.ai.exception;

/**
 * Thrown when a task type's Resilience4j circuit breaker is open — the recent failure rate breached
 * the threshold (5 failures in a 5-minute window, per {@code lld/ai.md} Flow 2), so the breaker
 * short-circuits the call <em>without hitting the wire</em> until its open window elapses and a
 * half-open probe succeeds.
 *
 * <p>Mapped to HTTP 503 by {@code AiExceptionHandler} (same status as {@link
 * AiUnavailableException} but a distinct {@code type} slug so admin tooling can tell "breaker open"
 * apart from "upstream 5xx / retries exhausted"). Calling modules treat it as a graceful-degrade
 * signal — identical handling to {@link AiUnavailableException}: the AI feature pauses, the system
 * stays operational.
 */
public class AiCircuitOpenException extends AiException {

  public AiCircuitOpenException(String message) {
    super(message);
  }

  public AiCircuitOpenException(String message, Throwable cause) {
    super(message, cause);
  }
}
