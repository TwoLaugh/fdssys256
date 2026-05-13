package com.example.mealprep.discovery.exception;

/**
 * Module-root exception for the discovery module. Per-failure subclasses (e.g. {@code
 * DiscoveryJobNotFoundException}, {@code DiscoverySourceNotFoundException}, {@code
 * DiscoveryAllSourcesUnavailableException}, {@code DiscoveryJobTimeoutException}) extend this so
 * the (deferred-to-01b) {@code DiscoveryExceptionHandler} can map either the specific subtype or
 * the root if a future subtype lands without an explicit handler.
 *
 * <p>Mirrors the recipe module's {@code RecipeException}: the project-wide {@code
 * MealPrepException} root hasn't been introduced yet, so we extend {@link RuntimeException}
 * directly per the same convention.
 */
public class DiscoveryException extends RuntimeException {

  public DiscoveryException(String message) {
    super(message);
  }

  public DiscoveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
