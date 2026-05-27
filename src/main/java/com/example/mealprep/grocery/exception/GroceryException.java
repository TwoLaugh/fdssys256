package com.example.mealprep.grocery.exception;

/**
 * Module-root exception for the grocery module. Per-failure subclasses extend this so the {@code
 * GroceryExceptionHandler} (or the {@code GlobalExceptionHandler} catch-all) can map either the
 * specific subtype or the root if a future subtype lands without a dedicated handler.
 *
 * <p>NOTE (ticket 01a divergence): the ticket cites {@code GroceryException extends
 * MealPrepException}, but the project-wide {@code MealPrepException} root does NOT exist yet —
 * every shipped module (recipe, discovery, provisions, adaptation, feedback) extends {@link
 * RuntimeException} directly with the same documented note. 01a follows the shipped convention;
 * re-parent to {@code MealPrepException} in a follow-up once the core root lands.
 */
public class GroceryException extends RuntimeException {

  public GroceryException(String message) {
    super(message);
  }

  public GroceryException(String message, Throwable cause) {
    super(message, cause);
  }
}
