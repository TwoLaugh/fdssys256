package com.example.mealprep.preference.exception;

/**
 * Thrown by callers that detect an invalid novelty-tolerance shape outside the Jakarta validator
 * boundary (e.g. an internal consumer re-checking modes after a DB read). Mapped to HTTP 400 by
 * {@code PreferenceExceptionHandler}.
 *
 * <p>The validator path uses {@link jakarta.validation.ConstraintViolationException} / {@link
 * org.springframework.web.bind.MethodArgumentNotValidException}; this exception is the imperative
 * variant for the rare in-service check.
 */
public class InvalidNoveltyToleranceException extends PreferenceException {

  private final String offendingMode;
  private final String offendingField;

  public InvalidNoveltyToleranceException(
      String message, String offendingMode, String offendingField) {
    super(message);
    this.offendingMode = offendingMode;
    this.offendingField = offendingField;
  }

  public String offendingMode() {
    return offendingMode;
  }

  public String offendingField() {
    return offendingField;
  }
}
