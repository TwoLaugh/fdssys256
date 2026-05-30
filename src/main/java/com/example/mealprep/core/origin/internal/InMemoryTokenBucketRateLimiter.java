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
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p><b>Eviction (core-7).</b> {@code PER_USER} scope creates one bucket per {@code (origin,
 * user)}, so an unbounded distinct-user population would grow the map without bound. To keep the
 * map size proportional to the <em>active</em> caller set rather than the all-time set, a bucket
 * whose window ended more than {@link #STALE_AFTER_WINDOWS} windows ago is pruned. Pruning runs
 * opportunistically — every {@link #PRUNE_EVERY_N_CALLS}th call triggers one cheap sweep — so the
 * steady-state cost of a single {@code tryAcquire} stays O(1) and idle keys are reclaimed without a
 * background thread. A stale key that becomes active again simply re-creates a fresh (full) bucket,
 * identical to a never-seen key, so eviction is behaviourally transparent.
 */
@Component
public class InMemoryTokenBucketRateLimiter {

  /**
   * A bucket is prunable once its window ended this many windows in the past — comfortably beyond
   * the point where the next take would refill it anyway, so pruning never evicts a bucket that
   * still carries live rejection state.
   */
  static final long STALE_AFTER_WINDOWS = 2L;

  /** Run an opportunistic prune sweep once every this many calls. */
  static final int PRUNE_EVERY_N_CALLS = 256;

  private final OriginProperties properties;
  private final Clock clock;
  private final ConcurrentMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();
  private final AtomicInteger callsSincePrune = new AtomicInteger();

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

    maybePrune(window);
    return Optional.ofNullable(rejection[0]);
  }

  /**
   * Opportunistically prune buckets whose window ended well in the past. Triggered once every
   * {@link #PRUNE_EVERY_N_CALLS} calls so the amortised per-call cost stays O(1); the sweep itself
   * is a single pass over the map removing keys idle for more than {@link #STALE_AFTER_WINDOWS}
   * windows.
   */
  private void maybePrune(Duration window) {
    if (callsSincePrune.incrementAndGet() < PRUNE_EVERY_N_CALLS) {
      return;
    }
    callsSincePrune.set(0);
    pruneStaleBuckets(window);
  }

  /**
   * Remove every bucket whose window ended more than {@link #STALE_AFTER_WINDOWS} windows before
   * now. Exposed (like {@link #resetForTesting()}) so the unit test can force a deterministic sweep
   * without firing {@link #PRUNE_EVERY_N_CALLS} calls; production code reaches it only via the
   * opportunistic {@link #maybePrune(Duration)} path.
   */
  public void pruneStaleBuckets(Duration window) {
    Instant cutoff = Instant.now(clock).minus(window.multipliedBy(STALE_AFTER_WINDOWS));
    buckets.entrySet().removeIf(e -> e.getValue().windowEnd.isBefore(cutoff));
  }

  // Visible for unit tests.

  /** Current number of live buckets. Test/observability hook; not used by production code. */
  public int bucketCount() {
    return buckets.size();
  }

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
