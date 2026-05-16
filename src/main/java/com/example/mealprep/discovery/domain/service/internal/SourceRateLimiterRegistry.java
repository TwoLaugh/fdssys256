package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-source rate-limiter registry. Lazily instantiates a Resilience4j {@code RateLimiter} per
 * {@code source_key}, configured from {@code DiscoverySource.requestsPerMinute}. {@link
 * #tryAcquire(String)} is non-blocking ({@code timeoutDuration = ZERO}) — the runner converts a
 * {@code false} into a {@code RATE_LIMITED} scrape row per LLD line 522.
 *
 * <p>Configuration changes apply on next instantiation per LLD line 564; the map is keyed by source
 * key and the runner does not mutate it mid-job.
 *
 * <p>Package-private — only the runner (01d) injects this.
 */
@Component
class SourceRateLimiterRegistry {

  private final DiscoverySourceRepository repository;
  private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

  SourceRateLimiterRegistry(DiscoverySourceRepository repository) {
    this.repository = repository;
  }

  /**
   * Try to acquire a permit for {@code sourceKey}. Returns {@code false} immediately if no token is
   * available; {@code true} if acquired. Returns {@code false} when the source row is missing — a
   * missing row means the runner shouldn't be calling, so deny.
   */
  boolean tryAcquire(String sourceKey) {
    RateLimiter limiter =
        limiters.computeIfAbsent(
            sourceKey,
            key ->
                repository
                    .findBySourceKey(key)
                    .map(row -> buildLimiter(key, row.getRequestsPerMinute()))
                    .orElse(null));
    if (limiter == null) {
      return false;
    }
    return limiter.acquirePermission();
  }

  private RateLimiter buildLimiter(String sourceKey, int requestsPerMinute) {
    RateLimiterConfig config =
        RateLimiterConfig.custom()
            .limitForPeriod(Math.max(requestsPerMinute, 1))
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ZERO)
            .build();
    return RateLimiter.of(sourceKey, config);
  }
}
