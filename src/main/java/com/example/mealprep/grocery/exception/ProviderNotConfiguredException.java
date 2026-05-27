package com.example.mealprep.grocery.exception;

/**
 * Thrown when a provider operation is attempted but the user has no enabled {@code
 * grocery_provider_state} for that provider. Maps to HTTP 422 — UI surfaces "configure Tesco in
 * Settings".
 */
public class ProviderNotConfiguredException extends GroceryException {

  private final String providerKey;

  public ProviderNotConfiguredException(String providerKey) {
    super("Provider '" + providerKey + "' is not configured or is disabled");
    this.providerKey = providerKey;
  }

  public String providerKey() {
    return providerKey;
  }
}
