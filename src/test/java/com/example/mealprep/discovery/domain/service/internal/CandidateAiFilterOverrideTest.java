package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that a user-supplied {@link CandidateAiFilter} bean supersedes the Noop default via
 * {@code @ConditionalOnMissingBean} — i.e. the SPI-with-Noop wiring works as advertised so a future
 * AI integration can drop in without caller changes.
 */
class CandidateAiFilterOverrideTest {

  @Test
  void userSuppliedBeanSupersedesNoop() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            UserFilterConfiguration.class, NoopCandidateAiFilterConfiguration.class)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(CandidateAiFilter.class);
              CandidateAiFilter filter = ctx.getBean(CandidateAiFilter.class);
              assertThat(filter).isInstanceOf(MarkerFilter.class);
            });
  }

  @Configuration
  static class UserFilterConfiguration {
    @Bean
    CandidateAiFilter userFilter() {
      return new MarkerFilter();
    }
  }

  static final class MarkerFilter implements CandidateAiFilter {
    @Override
    public List<DiscoveryCandidate> filter(
        List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId) {
      return candidates;
    }
  }
}
