package com.example.mealprep.core.origin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.origin.OriginProperties.RateLimitConfig;
import com.example.mealprep.core.origin.OriginProperties.Scope;
import com.example.mealprep.core.origin.internal.InMemoryTokenBucketRateLimiter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In-memory bucket pinning. Covers: PER_USER isolation between users, GLOBAL collapse to a single
 * bucket, refill when the window expires, unlimited path for origins absent from the config.
 *
 * <p>Time is faked via a mutable clock so we can advance past the window boundary without sleeping.
 */
class InMemoryTokenBucketRateLimiterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-05-21T10:00:00Z"));

  @Test
  void per_user_scope_allows_up_to_limit_then_rejects() {
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.AI_FEEDBACK, 3, Scope.PER_USER), clock);
    UUID userA = UUID.randomUUID();

    // First 3 succeed.
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();

    // 4th rejected with retry-after = remaining window.
    Optional<Duration> retry = limiter.tryAcquire(Origin.AI_FEEDBACK, userA);
    assertThat(retry).isPresent();
    assertThat(retry.get()).isGreaterThan(Duration.ZERO);
  }

  @Test
  void per_user_scope_isolates_users() {
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.AI_FEEDBACK, 1, Scope.PER_USER), clock);
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    // User A burns their single token.
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isPresent();

    // User B's bucket is independent.
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userB)).isEmpty();
  }

  @Test
  void global_scope_shares_one_bucket_across_users() {
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.SYSTEM_SCHEDULED, 2, Scope.GLOBAL), clock);
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    assertThat(limiter.tryAcquire(Origin.SYSTEM_SCHEDULED, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.SYSTEM_SCHEDULED, userB)).isEmpty();
    // Third call from anyone is rejected — global bucket has 2 tokens total.
    assertThat(limiter.tryAcquire(Origin.SYSTEM_SCHEDULED, userA)).isPresent();
  }

  @Test
  void bucket_refills_after_window_elapses() {
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.AI_FEEDBACK, 1, Scope.PER_USER), clock);
    UUID userA = UUID.randomUUID();

    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isPresent();

    // Advance past the configured 1-hour window.
    clock.advance(Duration.ofHours(2));

    // Fresh bucket → first call after refill succeeds.
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
  }

  @Test
  void origin_with_no_config_is_unlimited() {
    // No entry for AI_ADAPTATION in the props map → tryAcquire is a no-op success.
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.AI_FEEDBACK, 1, Scope.PER_USER), clock);
    UUID userA = UUID.randomUUID();

    for (int i = 0; i < 1000; i++) {
      assertThat(limiter.tryAcquire(Origin.AI_ADAPTATION, userA))
          .as("AI_ADAPTATION should be unlimited when absent from config")
          .isEmpty();
    }
  }

  @Test
  void reset_for_testing_clears_state() {
    InMemoryTokenBucketRateLimiter limiter =
        new InMemoryTokenBucketRateLimiter(
            propsWithLimit(Origin.AI_FEEDBACK, 1, Scope.PER_USER), clock);
    UUID userA = UUID.randomUUID();

    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isEmpty();
    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA)).isPresent();

    limiter.resetForTesting();

    assertThat(limiter.tryAcquire(Origin.AI_FEEDBACK, userA))
        .as("after reset, the bucket is fresh")
        .isEmpty();
  }

  private static OriginProperties propsWithLimit(Origin origin, int limit, Scope scope) {
    return new OriginProperties(
        new BigDecimal("0.5"),
        Duration.ofHours(1),
        Map.of(origin, new RateLimitConfig(limit, scope)),
        true);
  }

  /** Mutable test clock — instant pulled at construction, advanced manually by tests. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }
}
