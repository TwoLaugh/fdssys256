package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SourceRateLimiterRegistryTest {

  @Test
  void firstAcquisitionSucceedsWithinBudget() {
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setRequestsPerMinute(60);
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRateLimiterRegistry registry = new SourceRateLimiterRegistry(repo);

    assertThat(registry.tryAcquire("src_a")).isTrue();
  }

  @Test
  void exhaustsBudgetWithinMinute() {
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setRequestsPerMinute(3);
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRateLimiterRegistry registry = new SourceRateLimiterRegistry(repo);

    assertThat(registry.tryAcquire("src_a")).isTrue();
    assertThat(registry.tryAcquire("src_a")).isTrue();
    assertThat(registry.tryAcquire("src_a")).isTrue();
    assertThat(registry.tryAcquire("src_a")).isFalse();
  }

  @Test
  void distinctSourceKeysHaveIndependentBudgets() {
    DiscoverySource rowA = DiscoveryTestData.sampleSource("src_a");
    rowA.setRequestsPerMinute(1);
    DiscoverySource rowB = DiscoveryTestData.sampleSource("src_b");
    rowB.setRequestsPerMinute(1);
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(rowA));
    when(repo.findBySourceKey("src_b")).thenReturn(Optional.of(rowB));
    SourceRateLimiterRegistry registry = new SourceRateLimiterRegistry(repo);

    assertThat(registry.tryAcquire("src_a")).isTrue();
    assertThat(registry.tryAcquire("src_a")).isFalse();
    assertThat(registry.tryAcquire("src_b")).isTrue();
  }

  @Test
  void missingSourceRowYieldsFalse() {
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("missing")).thenReturn(Optional.empty());
    SourceRateLimiterRegistry registry = new SourceRateLimiterRegistry(repo);

    assertThat(registry.tryAcquire("missing")).isFalse();
  }
}
