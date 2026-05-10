package com.example.mealprep.recipe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for the URL import HTTP client. Caps body size + connection / read timeouts
 * defensively so a malicious or merely-slow upstream cannot pin a request thread.
 *
 * <p>Defaults are {@code 10000 ms} timeout and {@code 2_097_152} bytes (2 MiB) max body.
 */
@Configuration
@ConfigurationProperties(prefix = "mealprep.recipe.import.fetch")
public class RecipeImportConfig {

  /** Connect + read timeout in milliseconds. Defaults to 10 seconds. */
  private int timeoutMs = 10_000;

  /** Maximum response body size in bytes. Defaults to 2 MiB. */
  private long maxBytes = 2L * 1024L * 1024L;

  /** Maximum redirects to follow. Defaults to 3. */
  private int maxRedirects = 3;

  /** User-Agent string sent on every fetch. */
  private String userAgent = "MealPrepBot/1.0 (+https://mealprep.example.com)";

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  public void setMaxBytes(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  public int getMaxRedirects() {
    return maxRedirects;
  }

  public void setMaxRedirects(int maxRedirects) {
    this.maxRedirects = maxRedirects;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  /**
   * RestClient pre-configured with the timeouts. The User-Agent header is added per-request inside
   * {@code UrlFetcher} so we can inject the configured value.
   */
  @Bean
  public RestClient recipeImportRestClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeoutMs);
    factory.setReadTimeout(timeoutMs);
    return RestClient.builder().requestFactory(factory).build();
  }
}
