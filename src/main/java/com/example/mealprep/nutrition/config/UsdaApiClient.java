package com.example.mealprep.nutrition.config;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * USDA FoodData Central search client. Lives in {@code nutrition.config} so the {@code
 * springWebStaysInApi} ArchUnit rule is satisfied (Spring Web types are permitted only under {@code
 * api} or {@code config}).
 *
 * <p>Decorated with Resilience4j {@code @Retry} (2 attempts on 5xx via {@code searchFallback}) and
 * {@code @RateLimiter} (1000 req/h). The class is a {@code @Component} (not {@code final}) so
 * integration tests can inject a {@code @MockBean} stub instead of hitting the real internet.
 */
@Component
public class UsdaApiClient {

  private static final Logger log = LoggerFactory.getLogger(UsdaApiClient.class);

  private final RestClient restClient;
  private final String apiKey;

  public UsdaApiClient(RestClient.Builder builder, UsdaApiConfig config) {
    this.restClient = builder.baseUrl(config.getBaseUrl()).build();
    this.apiKey = config.getApiKey();
  }

  /**
   * Search USDA for {@code searchTerm}; returns the top-5 candidates wrapped in {@link
   * UsdaSearchResultDto}. HTTP 4xx other than 429 is swallowed and surfaces as {@link
   * Optional#empty()}; the Resilience4j retry decorator handles 5xx + transient errors with the
   * {@code searchFallback} method.
   */
  @Retry(name = "usda", fallbackMethod = "searchFallback")
  @RateLimiter(name = "usda")
  public Optional<UsdaSearchResultDto> search(String searchTerm) {
    try {
      UsdaSearchResultDto result =
          restClient
              .get()
              .uri(
                  b ->
                      b.path("/foods/search")
                          .queryParam("query", searchTerm)
                          .queryParam("pageSize", 5)
                          .queryParam("api_key", apiKey)
                          .build())
              .retrieve()
              .body(UsdaSearchResultDto.class);
      return Optional.ofNullable(result);
    } catch (HttpClientErrorException e) {
      log.warn("USDA 4xx for term='{}': {}", searchTerm, e.getStatusCode());
      return Optional.empty();
    }
  }

  @SuppressWarnings("unused")
  Optional<UsdaSearchResultDto> searchFallback(String searchTerm, Throwable t) {
    log.warn("USDA fallback for term='{}': {}", searchTerm, t.toString());
    return Optional.empty();
  }
}
