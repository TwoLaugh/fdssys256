package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes {@link DiscoverySource} beans by {@code key()} and reconciles them against the {@code
 * discovery_sources} table. The bean list is captured once at startup ({@code @PostConstruct}); the
 * DB read happens per call so admin toggles take effect on the next job, per LLD line 193 ("the
 * runner reads via {@code findAllEnabled} once per job and never mid-job").
 *
 * <p>Also surfaces the per-source circuit-breaker query and the bookkeeping helpers the runner
 * calls on success / failure (LLD line 565: {@code failure_streak >= 5} skips for an hour).
 *
 * <p>Package-private — only the runner (01d) injects this.
 */
@Component
class SourceRegistry {

  private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);
  private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
  private static final Duration CIRCUIT_BREAKER_COOLDOWN = Duration.ofHours(1);

  private final List<DiscoverySource> sources;
  private final DiscoverySourceRepository repository;
  private final Clock clock;
  private Map<String, DiscoverySource> byKey;

  SourceRegistry(List<DiscoverySource> sources, DiscoverySourceRepository repository, Clock clock) {
    this.sources = sources;
    this.repository = repository;
    this.clock = clock;
  }

  @PostConstruct
  void index() {
    byKey =
        sources.stream()
            .collect(Collectors.toUnmodifiableMap(DiscoverySource::key, Function.identity()));
    log.info("discovery source registry: {} bean(s) registered: {}", byKey.size(), byKey.keySet());
  }

  Optional<DiscoverySource> bySourceKey(String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  /**
   * All enabled DB rows whose {@code source_key} matches a registered bean. DB rows without a bean
   * are logged at WARN and skipped — silent skipping would mask a deploy-time wiring bug.
   */
  List<DiscoverySource> resolveEnabled() {
    return repository.findByEnabledTrue().stream()
        .map(this::beanForRowOrWarn)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /** Same as {@link #resolveEnabled()} but additionally filtered to {@code requestedKeys}. */
  List<DiscoverySource> resolveEnabledByKey(Collection<String> requestedKeys) {
    Set<String> requested = new HashSet<>(requestedKeys);
    return repository.findByEnabledTrue().stream()
        .filter(row -> requested.contains(row.getSourceKey()))
        .map(this::beanForRowOrWarn)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * Per-source circuit-breaker query (LLD line 565). Open when {@code failureStreak >= 5} AND the
   * last failure was within the cooldown window. Returns {@code false} when the DB row is absent —
   * a missing row is the runner's problem, not the circuit breaker's.
   */
  boolean isCircuitOpen(DiscoverySource source, Instant now) {
    return repository
        .findBySourceKey(source.key())
        .map(
            row ->
                row.getFailureStreak() >= CIRCUIT_BREAKER_THRESHOLD
                    && row.getLastFailureAt() != null
                    && Duration.between(row.getLastFailureAt(), now)
                            .compareTo(CIRCUIT_BREAKER_COOLDOWN)
                        < 0)
        .orElse(false);
  }

  @Transactional
  void recordSuccess(String sourceKey) {
    repository
        .findBySourceKey(sourceKey)
        .ifPresent(
            row -> {
              row.setLastSuccessAt(Instant.now(clock));
              row.setFailureStreak(0);
              repository.save(row);
            });
  }

  @Transactional
  void recordFailure(String sourceKey) {
    repository
        .findBySourceKey(sourceKey)
        .ifPresent(
            row -> {
              row.setLastFailureAt(Instant.now(clock));
              row.setFailureStreak(row.getFailureStreak() + 1);
              repository.save(row);
            });
  }

  private DiscoverySource beanForRowOrWarn(
      com.example.mealprep.discovery.domain.entity.DiscoverySource row) {
    DiscoverySource bean = byKey.get(row.getSourceKey());
    if (bean == null) {
      log.warn(
          "source row '{}' enabled but no @Component bean registered — skipping",
          row.getSourceKey());
    }
    return bean;
  }
}
