package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pre-call gate that rejects an AI dispatch when the per-user rolling-window cost cap would be
 * crossed. Window and limit come from {@link AiProperties.Budget}; {@code enabled=false}
 * short-circuits the check entirely (dev / test convenience).
 *
 * <p>Mirrors the shape of {@code LoginThrottleService} — windowed read against an audit table, then
 * a "throw with Retry-After computed from the oldest counted row" path on rejection. Concurrency
 * race window is acknowledged by {@code lld/ai.md} §Concurrency: a couple of stale-allowed calls is
 * acceptable given graceful-degrade semantics.
 */
@Component
public class CostBudgetGuard {

  /** Pence per micropence — used to convert the ledger's integer micropence back to pence. */
  private static final BigDecimal MICRO_PER_PENCE = BigDecimal.valueOf(1_000_000L);

  private final AiCallLogRepository repository;
  private final CostCalculator costCalculator;
  private final AiProperties properties;
  private final Clock clock;

  public CostBudgetGuard(
      AiCallLogRepository repository,
      CostCalculator costCalculator,
      AiProperties properties,
      Clock clock) {
    this.repository = repository;
    this.costCalculator = costCalculator;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Reject the dispatch with {@link AiCostBudgetExceededException} when the projected post-call
   * spend (existing rolling-window total + a coarse upper-bound estimate of the in-flight call)
   * meets or crosses the limit. The limit is exclusive: {@code spent + estimate >= limit} is a
   * rejection, matching the ticket's edge-case checklist.
   *
   * <p>System-initiated tasks (no {@code userId}) are exempt — there's nobody to bill them to.
   *
   * @throws AiCostBudgetExceededException when the cap is reached
   */
  public void checkOrThrow(AiTask<?> task) {
    AiProperties.Budget budget = properties.budget();
    if (!Boolean.TRUE.equals(budget.enabled())) {
      return;
    }
    UUID userId = task.userId().orElse(null);
    if (userId == null) {
      return;
    }
    Instant now = Instant.now(clock);
    Duration window = budget.window();
    Instant since = now.minus(window);
    long alreadySpentMicro = repository.sumCostMicroPenceForUserSince(userId, since);
    long estimateMicro = estimateMicroFor(task);
    long limitMicro = limitMicroPence(budget);

    if (alreadySpentMicro + estimateMicro < limitMicro) {
      return;
    }
    Duration retryAfter = retryAfterFor(userId, since, window, now);
    throw new AiCostBudgetExceededException(
        userId,
        microPenceToPence(alreadySpentMicro),
        microPenceToPence(limitMicro),
        window,
        retryAfter);
  }

  /**
   * Coarse upper-bound estimate for the in-flight call. We don't know token counts at pre-check
   * time; the ticket calls for "a coarse upper bound" — a fixed prompt+response budget per tier
   * keeps the math simple. Precision lands when the actual call's cost is logged.
   */
  long estimateMicroFor(AiTask<?> task) {
    ModelTier tier = task.tier();
    return costCalculator.estimate(tier, estimatedRequestTokens(tier), estimatedResponseTokens());
  }

  private static int estimatedRequestTokens(ModelTier tier) {
    // Coarse: 4k tokens of input is a reasonable upper bound for the prompts in this codebase.
    // Higher tiers tend to get larger contexts, but the cap exists to surface runaway spend, not
    // to model the median call exactly.
    return switch (tier) {
      case CHEAP -> 4_000;
      case MID -> 8_000;
      case HIGH -> 16_000;
    };
  }

  private static int estimatedResponseTokens() {
    // Output is typically smaller than input for our task surface; 2k as a conservative cap.
    return 2_000;
  }

  private long limitMicroPence(AiProperties.Budget budget) {
    long pence = budget.dailyPencePerUser();
    return Math.multiplyExact(pence, 1_000_000L);
  }

  /**
   * Time until the oldest SUCCEEDED row in the window exits, floored to one second. When no rows
   * exist (estimate alone tripped the cap — unusual but possible if the cap is set very low), defer
   * to the configured window length.
   */
  Duration retryAfterFor(UUID userId, Instant since, Duration window, Instant now) {
    List<AiCallLog> rows = repository.findSucceededForUserSinceOrderByCreatedAtAsc(userId, since);
    if (rows.isEmpty()) {
      return clampToOneSecond(window);
    }
    Instant oldest = rows.get(0).getCreatedAt();
    if (oldest == null) {
      return clampToOneSecond(window);
    }
    Instant releaseAt = oldest.plus(window);
    Duration remaining = Duration.between(now, releaseAt);
    return clampToOneSecond(remaining);
  }

  private static Duration clampToOneSecond(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      return Duration.ofSeconds(1);
    }
    long seconds = duration.toSeconds();
    if (duration.minusSeconds(seconds).toNanos() > 0) {
      seconds += 1;
    }
    if (seconds < 1) {
      seconds = 1;
    }
    return Duration.ofSeconds(seconds);
  }

  static BigDecimal microPenceToPence(long microPence) {
    return BigDecimal.valueOf(microPence).divide(MICRO_PER_PENCE, 2, RoundingMode.HALF_UP);
  }
}
