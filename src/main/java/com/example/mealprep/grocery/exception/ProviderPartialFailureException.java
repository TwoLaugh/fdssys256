package com.example.mealprep.grocery.exception;

/**
 * Signals that {@code placeOrder} succeeded for only a subset of lines. Per lld/grocery.md lines
 * 755 / 764, this is DELIBERATELY NOT an error — it maps to a 200 response with the
 * partially-filled order in the body ("fail forward"). It is caught service-side and converted to a
 * {@code PLACED_PARTIAL} order; there is intentionally NO {@code @ExceptionHandler} entry for it in
 * {@code GroceryExceptionHandler}.
 *
 * <p>Carried here so the Tier-3 service (grocery-01e) can throw/catch it internally.
 */
public class ProviderPartialFailureException extends GroceryException {

  public ProviderPartialFailureException(String message) {
    super(message);
  }
}
