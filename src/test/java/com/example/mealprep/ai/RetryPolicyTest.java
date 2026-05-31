package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.service.internal.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exhaustive unit coverage of the {@link RetryPolicy} classifier — the pure mapping of an Anthropic
 * HTTP status onto a failure {@link RetryPolicy.Category}, the retry decision, and the per-category
 * backoff. Per {@code lld/ai.md} Flow 2.
 */
class RetryPolicyTest {

  // ---- classification ----

  @Test
  void status429_isRateLimit_andRetryable() {
    assertThat(RetryPolicy.classifyStatus(429)).isEqualTo(RetryPolicy.Category.RATE_LIMIT);
    assertThat(RetryPolicy.isRetryableStatus(429)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void authStatuses_areAuth_andNotRetryable(int status) {
    assertThat(RetryPolicy.classifyStatus(status)).isEqualTo(RetryPolicy.Category.AUTH);
    assertThat(RetryPolicy.isRetryableStatus(status)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 409, 422})
  void otherClientErrors_areUnknown_andNotRetryable(int status) {
    assertThat(RetryPolicy.classifyStatus(status)).isEqualTo(RetryPolicy.Category.UNKNOWN);
    assertThat(RetryPolicy.isRetryableStatus(status)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void serverErrors_areTimeout_andRetryable(int status) {
    assertThat(RetryPolicy.classifyStatus(status)).isEqualTo(RetryPolicy.Category.TIMEOUT);
    assertThat(RetryPolicy.isRetryableStatus(status)).isTrue();
  }

  @Test
  void belowFourHundred_nonSuccess_isTreatedAsTransient() {
    // 1xx/3xx never expected on the Messages API, but if one arrives it must not be a fatal 4xx.
    assertThat(RetryPolicy.classifyStatus(199)).isEqualTo(RetryPolicy.Category.TIMEOUT);
    assertThat(RetryPolicy.classifyStatus(302)).isEqualTo(RetryPolicy.Category.TIMEOUT);
  }

  // ---- category retryability flags ----

  @Test
  void retryableCategories_areRateLimitTimeoutSemantic_only() {
    assertThat(RetryPolicy.Category.RATE_LIMIT.retryable()).isTrue();
    assertThat(RetryPolicy.Category.TIMEOUT.retryable()).isTrue();
    assertThat(RetryPolicy.Category.SEMANTIC.retryable()).isTrue();
    assertThat(RetryPolicy.Category.AUTH.retryable()).isFalse();
    assertThat(RetryPolicy.Category.POLICY.retryable()).isFalse();
    assertThat(RetryPolicy.Category.UNKNOWN.retryable()).isFalse();
  }

  // ---- backoff ----

  @ParameterizedTest
  @CsvSource({"1,1000", "2,2000", "3,4000"})
  void rateLimitBackoff_usesLongerBase_doublingPerAttempt(int attempt, long expectedMs) {
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.RATE_LIMIT, attempt))
        .isEqualTo(Duration.ofMillis(expectedMs));
  }

  @ParameterizedTest
  @CsvSource({"1,200", "2,400", "3,800"})
  void timeoutBackoff_usesShortBase_doublingPerAttempt(int attempt, long expectedMs) {
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.TIMEOUT, attempt))
        .isEqualTo(Duration.ofMillis(expectedMs));
  }

  @Test
  void rateLimitBackoff_isStrictlyLongerThanTimeoutBackoff_atEveryAttempt() {
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.RATE_LIMIT, attempt))
          .isGreaterThan(RetryPolicy.backoffFor(RetryPolicy.Category.TIMEOUT, attempt));
    }
  }

  @Test
  void nonRetryableCategories_haveZeroBackoff() {
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.AUTH, 1)).isEqualTo(Duration.ZERO);
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.POLICY, 1)).isEqualTo(Duration.ZERO);
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.UNKNOWN, 1)).isEqualTo(Duration.ZERO);
  }

  @Test
  void backoff_attemptZeroOrNegative_doesNotUnderflowShift() {
    // Defensive: a 0/negative attempt clamps the shift to 0, yielding the base value.
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.TIMEOUT, 0))
        .isEqualTo(Duration.ofMillis(RetryPolicy.TRANSIENT_BASE_BACKOFF_MS));
    assertThat(RetryPolicy.backoffFor(RetryPolicy.Category.RATE_LIMIT, -3))
        .isEqualTo(Duration.ofMillis(RetryPolicy.RATE_LIMIT_BASE_BACKOFF_MS));
  }

  // ---- jitter (ai-9) ----

  @ParameterizedTest
  @CsvSource({"1,1000", "2,2000", "3,4000"})
  void backoffWithJitter_staysInHalfCeilingToCeilingBand_rateLimit(int attempt, long ceilingMs) {
    java.util.random.RandomGenerator rng = new java.util.Random(123);
    // Sample many times — every draw must land in [ceiling/2, ceiling] and never collapse to zero.
    for (int i = 0; i < 200; i++) {
      long ms =
          RetryPolicy.backoffWithJitter(RetryPolicy.Category.RATE_LIMIT, attempt, rng).toMillis();
      assertThat(ms).isBetween(ceilingMs / 2, ceilingMs);
    }
  }

  @Test
  void backoffWithJitter_minDraw_isHalfCeiling_maxDraw_isCeiling() {
    // A generator returning 0 → exactly ceiling/2; returning bound-1 → exactly ceiling.
    java.util.random.RandomGenerator min =
        new java.util.random.RandomGenerator() {
          @Override
          public long nextLong() {
            return 0;
          }

          @Override
          public long nextLong(long bound) {
            return 0;
          }
        };
    java.util.random.RandomGenerator max =
        new java.util.random.RandomGenerator() {
          @Override
          public long nextLong() {
            return Long.MAX_VALUE;
          }

          @Override
          public long nextLong(long bound) {
            return bound - 1;
          }
        };
    assertThat(RetryPolicy.backoffWithJitter(RetryPolicy.Category.TIMEOUT, 1, min))
        .isEqualTo(Duration.ofMillis(100)); // 200/2
    assertThat(RetryPolicy.backoffWithJitter(RetryPolicy.Category.TIMEOUT, 1, max))
        .isEqualTo(Duration.ofMillis(200)); // full ceiling
  }

  @Test
  void backoffWithJitter_nonRetryableCategories_areZero() {
    java.util.random.RandomGenerator rng = new java.util.Random(1);
    assertThat(RetryPolicy.backoffWithJitter(RetryPolicy.Category.AUTH, 1, rng))
        .isEqualTo(Duration.ZERO);
    assertThat(RetryPolicy.backoffWithJitter(RetryPolicy.Category.POLICY, 1, rng))
        .isEqualTo(Duration.ZERO);
  }
}
