package com.example.mealprep.household.spi.internal;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.household.spi.SoftPreferencesReader;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback {@link SoftPreferencesReader} that returns no bundles. Wired only when no other {@code
 * SoftPreferencesReader} bean is present (e.g. before preference-01c lands).
 *
 * <p>Implementation note: the SPI binding is declared via a {@code @Configuration} class with a
 * {@code @Bean @ConditionalOnMissingBean(SoftPreferencesReader.class)} method (rather than putting
 * {@code @Component @ConditionalOnMissingBean} on the class itself). The {@code @Component} variant
 * doesn't reliably defer to a test-time {@code @TestConfiguration}-provided bean — the conditional
 * misses the override during component-scan and registers the noop unconditionally. The
 * {@code @Bean} variant evaluates after bean definition gathering, so the real implementation
 * always wins when present.
 */
@Configuration
class NoopSoftPreferencesReaderConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(NoopSoftPreferencesReaderConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(SoftPreferencesReader.class)
  SoftPreferencesReader noopSoftPreferencesReader() {
    return new NoopSoftPreferencesReader();
  }

  /** Package-private impl — never directly injected; callers go through the SPI interface. */
  static class NoopSoftPreferencesReader implements SoftPreferencesReader {
    @Override
    public List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds) {
      log.debug(
          "NoopSoftPreferencesReader returning empty bundles for {} userIds — preference-01c not yet wired",
          userIds == null ? 0 : userIds.size());
      return List.of();
    }
  }
}
