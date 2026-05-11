package com.example.mealprep.nutrition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binding for {@code mealprep.nutrition.open-food-facts} properties. No API key — OFF's search
 * endpoint is publicly accessible.
 */
@Configuration
@ConfigurationProperties(prefix = "mealprep.nutrition.open-food-facts")
public class OpenFoodFactsConfig {

  private String baseUrl = "https://world.openfoodfacts.org";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }
}
