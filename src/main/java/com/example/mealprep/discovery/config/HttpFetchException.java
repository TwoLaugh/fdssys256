package com.example.mealprep.discovery.config;

/**
 * Spring-Web-free signal raised by {@link DiscoveryHttpFetcher}. Lets the {@code
 * discovery.source..} adapters reason about HTTP failures (status code, network error) without
 * importing {@code org.springframework.web..} types — see the class doc on {@link
 * DiscoveryHttpFetcher} for why that matters ({@code springWebStaysInApi} ArchUnit rule).
 *
 * <p>Adapters translate this into the SPI's {@code DiscoverySourceUnavailableException} (search
 * path) or {@code ExtractionFailedException} (fetch path).
 */
public class HttpFetchException extends RuntimeException {

  private final Integer statusCode;

  public HttpFetchException(String message, Integer statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /** HTTP status when a response was received; {@code null} for network errors / timeouts. */
  public Integer statusCode() {
    return statusCode;
  }
}
