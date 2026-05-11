package com.example.mealprep.nutrition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binding for {@code mealprep.nutrition.usda} properties: the FoodData Central base URL and the API
 * key. The IT suite sets {@code apiKey} to a placeholder string and mocks the HTTP call via
 * WireMock; no live key is committed.
 */
@Configuration
@ConfigurationProperties(prefix = "mealprep.nutrition.usda")
public class UsdaApiConfig {

  private String apiKey = "test-key-not-real";
  private String baseUrl = "https://api.nal.usda.gov/fdc/v1";

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }
}
