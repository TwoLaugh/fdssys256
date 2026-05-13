package com.example.mealprep.discovery.exception;

import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@code startJob} when the resolved source set is empty, when one or more requested
 * {@code sourceKeys} are unknown / disabled, or when any other service-layer (DB-dependent)
 * constraint check fails. Mapped to HTTP 422 by {@code DiscoveryExceptionHandler}.
 *
 * <p>Carries an optional {@code errors} list — the offending source keys — surfaced as a
 * ProblemDetail extension property so the client can show the user the precise inputs that failed.
 */
public class DiscoveryConstraintInvalidException extends DiscoveryException {

  private final List<String> errors;

  public DiscoveryConstraintInvalidException(String detail) {
    super(detail);
    this.errors = Collections.emptyList();
  }

  public DiscoveryConstraintInvalidException(String detail, List<String> errors) {
    super(detail);
    this.errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
