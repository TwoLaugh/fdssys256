package com.example.mealprep.grocery.domain.service.internal.providers;

/**
 * CHECKED exception raised by a {@link GroceryProvider} when the provider is unavailable — provider
 * down, login expired, or an infrastructure failure. Per lld/grocery.md line 672 the SPI-level
 * provider exceptions are checked so the compiler FORCES the calling service to catch + surface
 * them at every provider call site (never blindly retried).
 *
 * <p>This is the SPI-internal signal. The Tier-3 service catches it and re-surfaces the
 * HTTP-mapped, unchecked {@link
 * com.example.mealprep.grocery.exception.ProviderUnavailableException} (mapped to 503 by {@code
 * GroceryExceptionHandler}). {@code reason} carries the machine-readable cause (e.g. {@code
 * login_required}).
 */
public class ProviderUnavailableException extends Exception {

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
