package com.example.mealprep.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configures the {@link RestClient} used by {@code AnthropicClient}. We use Spring's stock REST
 * client rather than an Anthropic-specific SDK; the Messages API is straightforward JSON-over-HTTPS
 * and pulling a vendor SDK adds a transitive surface we'd rather avoid.
 *
 * <p>The {@code Authorization} / {@code x-api-key} headers are set per-request inside the client
 * (so the configured base URL alone is the only state held on the client bean) — keeps the
 * sensitive key off the bean definition and out of any actuator dump.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AnthropicSdkConfig {

  @Bean
  public RestClient anthropicRestClient(AiProperties properties) {
    return RestClient.builder().baseUrl(properties.anthropicBaseUrl()).build();
  }
}
