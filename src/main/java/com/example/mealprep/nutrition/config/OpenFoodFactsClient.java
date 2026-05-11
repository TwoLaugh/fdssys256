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
 * Open Food Facts search client. Lives in {@code nutrition.config} alongside {@link UsdaApiClient}.
 *
 * <p>{@code @Retry(name = "off")} (2 attempts on 5xx) + {@code @RateLimiter(name = "off")} (100
 * req/h — OFF documents a lower soft limit than USDA; conservative). Public endpoint, no API key.
 */
@Component
public class OpenFoodFactsClient {

  private static final Logger log = LoggerFactory.getLogger(OpenFoodFactsClient.class);

  private final RestClient restClient;

  public OpenFoodFactsClient(RestClient.Builder builder, OpenFoodFactsConfig config) {
    this.restClient = builder.baseUrl(config.getBaseUrl()).build();
  }

  @Retry(name = "off", fallbackMethod = "searchFallback")
  @RateLimiter(name = "off")
  public Optional<OffSearchResultDto> search(String searchTerm) {
    try {
      OffSearchResultDto result =
          restClient
              .get()
              .uri(
                  b ->
                      b.path("/cgi/search.pl")
                          .queryParam("search_terms", searchTerm)
                          .queryParam("json", 1)
                          .queryParam("page_size", 5)
                          .build())
              .retrieve()
              .body(OffSearchResultDto.class);
      return Optional.ofNullable(result);
    } catch (HttpClientErrorException e) {
      log.warn("OFF 4xx for term='{}': {}", searchTerm, e.getStatusCode());
      return Optional.empty();
    }
  }

  @SuppressWarnings("unused")
  Optional<OffSearchResultDto> searchFallback(String searchTerm, Throwable t) {
    log.warn("OFF fallback for term='{}': {}", searchTerm, t.toString());
    return Optional.empty();
  }
}
