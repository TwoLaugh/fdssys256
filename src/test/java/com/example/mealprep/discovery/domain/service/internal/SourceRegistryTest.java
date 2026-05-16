package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SourceRegistryTest {

  private static final Instant NOW = Instant.parse("2026-05-13T12:00:00Z");
  private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void indexesBeansByKeyAtStartup() {
    StubSource a = new StubSource("src_a");
    StubSource b = new StubSource("src_b");
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    SourceRegistry registry = new SourceRegistry(List.of(a, b), repo, fixedClock);
    registry.index();

    assertThat(registry.bySourceKey("src_a")).containsSame(a);
    assertThat(registry.bySourceKey("src_b")).containsSame(b);
    assertThat(registry.bySourceKey("missing")).isEmpty();
  }

  @Test
  void emptyBeanListResolvesToEmptyEnabledList() {
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findByEnabledTrue()).thenReturn(List.of());
    SourceRegistry registry = new SourceRegistry(List.of(), repo, fixedClock);
    registry.index();

    assertThat(registry.resolveEnabled()).isEmpty();
  }

  @Test
  void enabledRowWithoutBeanIsWarnLoggedAndSkipped() {
    StubSource a = new StubSource("src_a");
    DiscoverySource rowWithoutBean = DiscoveryTestData.sampleSource("src_missing");
    DiscoverySource rowWithBean = DiscoveryTestData.sampleSource("src_a");
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findByEnabledTrue()).thenReturn(List.of(rowWithoutBean, rowWithBean));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    List<com.example.mealprep.discovery.domain.service.DiscoverySource> resolved =
        registry.resolveEnabled();

    assertThat(resolved).containsExactly(a);
  }

  @Test
  void resolveEnabledByKeyFiltersToRequestedSubset() {
    StubSource a = new StubSource("src_a");
    StubSource b = new StubSource("src_b");
    DiscoverySource rowA = DiscoveryTestData.sampleSource("src_a");
    DiscoverySource rowB = DiscoveryTestData.sampleSource("src_b");
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findByEnabledTrue()).thenReturn(List.of(rowA, rowB));
    SourceRegistry registry = new SourceRegistry(List.of(a, b), repo, fixedClock);
    registry.index();

    assertThat(registry.resolveEnabledByKey(List.of("src_a"))).containsExactly(a);
  }

  @Test
  void isCircuitOpenTrueWhenStreakAtThresholdAndRecent() {
    StubSource a = new StubSource("src_a");
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setFailureStreak(5);
    row.setLastFailureAt(NOW.minus(Duration.ofMinutes(30)));
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    assertThat(registry.isCircuitOpen(a, NOW)).isTrue();
  }

  @Test
  void isCircuitOpenFalseAfterCooldown() {
    StubSource a = new StubSource("src_a");
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setFailureStreak(5);
    row.setLastFailureAt(NOW.minus(Duration.ofHours(2)));
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    assertThat(registry.isCircuitOpen(a, NOW)).isFalse();
  }

  @Test
  void isCircuitOpenFalseBelowThreshold() {
    StubSource a = new StubSource("src_a");
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setFailureStreak(4);
    row.setLastFailureAt(NOW.minus(Duration.ofMinutes(1)));
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    assertThat(registry.isCircuitOpen(a, NOW)).isFalse();
  }

  @Test
  void recordSuccessResetsFailureStreakAndStampsTimestamp() {
    StubSource a = new StubSource("src_a");
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setFailureStreak(3);
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    registry.recordSuccess("src_a");

    assertThat(row.getFailureStreak()).isZero();
    assertThat(row.getLastSuccessAt()).isEqualTo(NOW);
    verify(repo, times(1)).save(row);
  }

  @Test
  void recordFailureIncrementsStreakAndStampsTimestamp() {
    StubSource a = new StubSource("src_a");
    DiscoverySource row = DiscoveryTestData.sampleSource("src_a");
    row.setFailureStreak(2);
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey("src_a")).thenReturn(Optional.of(row));
    SourceRegistry registry = new SourceRegistry(List.of(a), repo, fixedClock);
    registry.index();

    registry.recordFailure("src_a");

    assertThat(row.getFailureStreak()).isEqualTo(3);
    assertThat(row.getLastFailureAt()).isEqualTo(NOW);
    verify(repo, times(1)).save(row);
  }

  @Test
  void recordSuccessOnMissingRowIsNoop() {
    DiscoverySourceRepository repo = Mockito.mock(DiscoverySourceRepository.class);
    when(repo.findBySourceKey(any())).thenReturn(Optional.empty());
    SourceRegistry registry = new SourceRegistry(List.of(), repo, fixedClock);
    registry.index();

    registry.recordSuccess("missing");

    verify(repo, never()).save(any());
  }

  /** Minimal {@code DiscoverySource} bean for unit-test purposes. */
  private static final class StubSource
      implements com.example.mealprep.discovery.domain.service.DiscoverySource {
    private final String key;

    StubSource(String key) {
      this.key = key;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public DiscoverySourceKind kind() {
      return DiscoverySourceKind.SITEMAP;
    }

    @Override
    public List<DiscoveryCandidate> search(DiscoveryQuery query) {
      return List.of();
    }

    @Override
    public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
      throw new UnsupportedOperationException();
    }
  }
}
