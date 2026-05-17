package com.example.mealprep.discovery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP-client beans for the discovery module. Lives in {@code discovery.config} (NOT {@code
 * domain.service.internal}) per the project-wide {@code springWebStaysInApi} ArchUnit rule — Spring
 * Web types belong to the {@code api}/{@code config} layer.
 *
 * <p>Ships:
 *
 * <ul>
 *   <li>{@code robotsRestClient} — 5s timeouts; consumed by {@code InHouseRobotsTxtGate}. The gate
 *       is on the synchronous critical path of the runner and we'd rather give up and treat as
 *       {@code UNAVAILABLE} than block.
 *   <li>{@code discoveryRestClient} — 10s timeouts; consumed (via {@link DiscoveryHttpFetcher}) by
 *       the {@code discovery.source..} adapters for SERP calls + page fetches (01e).
 * </ul>
 *
 * <p>{@code @EnableConfigurationProperties(GoogleCustomSearchConfig.class)} registers the Google
 * CSE config record (01e) alongside {@code DiscoveryProperties} (registered by {@code
 * DiscoveryAsyncConfig}).
 */
@Configuration
@EnableConfigurationProperties(GoogleCustomSearchConfig.class)
public class DiscoveryHttpConfig {

  @Bean
  public RestClient robotsRestClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(5_000);
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Shared client for SERP queries and arbitrary recipe-page fetches. 10s connect + read timeouts
   * per 01e ticket invariant (MOD on this file). Distinct bean from {@code robotsRestClient} so the
   * two concerns can be tuned independently.
   */
  @Bean
  public RestClient discoveryRestClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(10_000);
    factory.setReadTimeout(10_000);
    return RestClient.builder().requestFactory(factory).build();
  }
}
