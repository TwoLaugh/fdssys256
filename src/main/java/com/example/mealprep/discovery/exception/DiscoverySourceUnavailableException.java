package com.example.mealprep.discovery.exception;

/**
 * Permanent source-level failure thrown by a {@code DiscoverySource} implementation. Caught by the
 * runner (01d) and converted to a {@code SOURCE_UNAVAILABLE} scrape row — NOT mapped in {@code
 * DiscoveryExceptionHandler} because this exception never reaches the controller layer.
 */
public class DiscoverySourceUnavailableException extends DiscoveryException {

  private final String sourceKey;

  public DiscoverySourceUnavailableException(String sourceKey, String reason, Throwable cause) {
    super("discovery source '" + sourceKey + "' unavailable: " + reason, cause);
    this.sourceKey = sourceKey;
  }

  public String getSourceKey() {
    return sourceKey;
  }
}
