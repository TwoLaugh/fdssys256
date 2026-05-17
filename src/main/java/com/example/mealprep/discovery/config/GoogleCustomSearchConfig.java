package com.example.mealprep.discovery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the Google Custom Search JSON API adapter — bound to {@code
 * mealprep.discovery.search.google.*}. Per the 01e ticket invariant 22 and {@code
 * lld/recipe-extraction-pipeline.md} line 341.
 *
 * <p>Secrets ({@link #apiKey}) come from an environment variable via the property placeholder in
 * {@code application.properties} ({@code ${MEALPREP_GOOGLE_CSE_KEY:placeholder}}). The {@code
 * placeholder} default keeps the dev/test profile bootable without credentials; the adapter's first
 * real CSE call then fails 401 → {@code DiscoverySourceUnavailableException} (graceful degradation
 * rather than fail-fast at startup — worth user review).
 *
 * <p>{@link #userAgent} defaults (when blank) to the project crawler UA so the bean is usable with
 * only the key/cx supplied.
 */
@ConfigurationProperties(prefix = "mealprep.discovery.search.google")
@Validated
public record GoogleCustomSearchConfig(
    @NotBlank String apiKey,
    @NotBlank String searchEngineId,
    @Min(1) @Max(10) int resultsPerQuery,
    @Min(1) int maxQueriesPerDay,
    String userAgent) {

  /** Default crawler User-Agent per {@code recipe-extraction-pipeline.md} line 313. */
  public static final String DEFAULT_USER_AGENT =
      "MealPrepAI/1.0 (+https://mealprep.example.com/bot)";

  public GoogleCustomSearchConfig {
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = DEFAULT_USER_AGENT;
    }
  }
}
