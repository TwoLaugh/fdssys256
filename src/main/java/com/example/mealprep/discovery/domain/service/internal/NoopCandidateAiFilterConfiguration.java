package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPI-with-Noop wiring for {@link CandidateAiFilter}. The {@code @Bean @ConditionalOnMissingBean}
 * recipe — NOT {@code @Component @ConditionalOnMissingBean} on the impl class — is required to
 * avoid the round-5 bug-1 race (component-scan order vs test-config registration order).
 *
 * <p>Class name {@code NoopCandidateAiFilterConfiguration} (bean {@code
 * noopCandidateAiFilterConfiguration}) and factory method {@code defaultCandidateAiFilter} (bean
 * {@code defaultCandidateAiFilter}) produce DISTINCT bean names — round-5 bug-2 avoidance.
 */
@Configuration
public class NoopCandidateAiFilterConfiguration {

  @Bean
  @ConditionalOnMissingBean(CandidateAiFilter.class)
  CandidateAiFilter defaultCandidateAiFilter() {
    return new PassThroughCandidateAiFilter();
  }

  /**
   * Pass-through pre-AI placeholder. Returns the input list unchanged and logs a WARN on every call
   * so production logs surface the missing AI integration loud and clear. Per LLD line 421 ("When
   * the prompt and integration land, the bean is replaced — no caller change.").
   */
  static class PassThroughCandidateAiFilter implements CandidateAiFilter {

    private static final Logger log = LoggerFactory.getLogger(PassThroughCandidateAiFilter.class);

    @Override
    public List<DiscoveryCandidate> filter(
        List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId) {
      log.warn(
          "CandidateAiFilter pass-through: returning {} candidates unfiltered (v1)",
          candidates.size());
      return candidates;
    }
  }
}
