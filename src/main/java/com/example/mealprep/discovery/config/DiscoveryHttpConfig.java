package com.example.mealprep.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP-client beans for the discovery module. Lives in {@code discovery.config} (NOT {@code
 * domain.service.internal}) per the project-wide {@code springWebStaysInApi} ArchUnit rule — Spring
 * Web types belong to the {@code api}/{@code config} layer.
 *
 * <p>Ships the {@code robotsRestClient} {@link RestClient} consumed by {@code
 * InHouseRobotsTxtGate}. 5s connect + read timeouts; the gate is on the synchronous critical path
 * of the runner and we'd rather give up and treat as {@code UNAVAILABLE} than block.
 */
@Configuration
public class DiscoveryHttpConfig {

  @Bean
  public RestClient robotsRestClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(5_000);
    return RestClient.builder().requestFactory(factory).build();
  }
}
