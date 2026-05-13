package com.example.mealprep.discovery.exception;

/**
 * Thrown when admin enable/disable or {@code GET /sources/{sourceKey}} addresses a source key that
 * doesn't exist in {@code discovery_sources}. Mapped to HTTP 404 by {@code
 * DiscoveryExceptionHandler}.
 */
public class DiscoverySourceNotFoundException extends DiscoveryException {

  private final String sourceKey;

  public DiscoverySourceNotFoundException(String sourceKey) {
    super("Discovery source not found: " + sourceKey);
    this.sourceKey = sourceKey;
  }

  public String sourceKey() {
    return sourceKey;
  }
}
