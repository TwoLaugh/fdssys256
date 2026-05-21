package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real {@link AiCandidateAiFilter} backed by {@code AiService}. Per ticket discovery-01g
 * §15, this configuration replaces the pre-01g {@code NoopCandidateAiFilterConfiguration}
 * placeholder.
 *
 * <p>The bean is registered unconditionally — there is no longer a SPI-with-Noop fallback. Tests
 * relying on the {@code TestAiService} (auto-registered for {@code @Profile("test")}) will get the
 * AI filter pointed at the test stub; tests can override the bean via a user configuration to
 * substitute a different filter implementation.
 */
@Configuration
public class AiCandidateAiFilterConfiguration {

  @Bean
  CandidateAiFilter candidateAiFilter(AiService aiService, DiscoveryProperties properties) {
    return new AiCandidateAiFilter(aiService, properties);
  }
}
