package com.example.mealprep.core.origin.internal;

import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.core.origin.OriginProperties;
import com.example.mealprep.core.origin.OriginProperties.RateLimitConfig;
import com.example.mealprep.core.origin.OriginProperties.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory per-origin token bucket. Single-instance v1 deploy — buckets are process-local; a
 * multi-node deployment would need Redis (out of scope, see ticket "What's NOT in scope" → "in-
 * memory token bucket suffices for single-instance v1").
 *
 * <p>Each bucket refills atomically on its first take after the window elapses; intermediate takes
 * just decrement. No background thread / scheduler — pull-based refill keeps the rate limiter cheap
 * when the system is idle.
 *
 * <p>Key shape:
 *
 * <ul>
 *   <li>{@link Scope#PER_USER} → one bucket per {@code (origin, actingAsUserId)} tuple
 *   <li>{@link Scope#GLOBAL} → one bucket per origin (the user id slot is null)
 * </ul>
 *
 * <p>Concurrency: {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} on the
 * bucket guarantees the decrement-or-refuse decision is serialised per key.
 */
@Component
public class InMemoryTokenBucketRateLimiter {

  private final OriginProperties properties;
  private final Clock clock;
  private final ConcurrentMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();

  public InMemoryTokenBucketRateLimiter(OriginProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Try to consume one token for the call. Returns empty when allowed; returns a non-empty Optional
   * carrying the retry-after duration when rejected.
   *
   * <p>Origins with no entry in {@code mealprep.origin.rate-limits.*} are unlimited — the method
   * returns empty immediately.
   */
  public Optional<Duration> tryAcquire(Origin origin, UUID actingAsUserId) {
    RateLimitConfig config = properties.rateLimits().get(origin);
    if (config == null) {
      return Optional.empty();
    }
    BucketKey key = new BucketKey(origin, config.scope() == Scope.PER_USER ? actingAsUserId : null);
    Duration window = properties.rateLimitWindow();
    int limit = config.limit();

    // compute() serialises updates on this key. We mutate a small mutable container (Bucket) to
    // avoid allocating a new object every call (steady state is a take + decrement).
    Duration[] rejection = new Duration[1];
    buckets.compute(
        key,
        (k, existing) -> {
          Instant now = Instant.now(clock);
          Bucket bucket = existing;
          if (bucket == null || !now.isBefore(bucket.windowEnd)) {
            // Fresh bucket OR previous window has elapsed: refill.
            bucket = new Bucket(limit, now.plus(window));
          }
          if (bucket.remaining > 0) {
            bucket.remaining--;
            return bucket;
          }
          // Exhausted within this window — reject with time-until-refill.
          rejection[0] = Duration.between(now, bucket.windowEnd);
          return bucket;
        });
    return Optional.ofNullable(rejection[0]);
  }

  // Visible for unit tests.

  /** Clear all buckets (test fixture; never called by production code). */
  public void resetForTesting() {
    buckets.clear();
  }

  private record BucketKey(Origin origin, UUID actingAsUserId) {}

  private static final class Bucket {
    int remaining;
    final Instant windowEnd;

    Bucket(int remaining, Instant windowEnd) {
      this.remaining = remaining;
      this.windowEnd = windowEnd;
    }
  }
}
