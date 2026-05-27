package com.example.mealprep.grocery.exception;

/**
 * Thrown when the grocery provider is unavailable — provider down, login expired, or infrastructure
 * failure. Maps to HTTP 503; the order moves to {@code PROVIDER_UNAVAILABLE}. Per lld/grocery.md
 * line 754.
 *
 * <p>NOTE: the LLD locates the provider exceptions in {@code domain.service.internal.providers}
 * (the {@code GroceryProvider} SPI package, which ships in grocery-01e) and models them as checked
 * exceptions. 01a ships them in {@code grocery.exception} as {@link GroceryException} subclasses so
 * the {@code GroceryExceptionHandler} can map them now; 01e wires them to the SPI's throw sites.
 * {@code reason} carries the machine-readable cause (e.g. {@code login_required}).
 */
public class ProviderUnavailableException extends GroceryException {

  private final String providerKey;
  private final String reason;

  public ProviderUnavailableException(String providerKey, String reason, String message) {
    super(message);
    this.providerKey = providerKey;
    this.reason = reason;
  }

  public String providerKey() {
    return providerKey;
  }

  public String reason() {
    return reason;
  }
}
