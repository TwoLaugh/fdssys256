package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.event.BudgetScope;
import com.example.mealprep.ai.event.CostBudgetExceededEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Pre-call gate enforcing the two-scope cost model (lld/ai.md Flow 1 step 5 + Decisions §7):
 *
 * <ul>
 *   <li><b>MONTHLY_TOTAL</b> — system-wide spend over the rolling monthly window. Hard block by
 *       default ({@code mealprep.ai.budget.monthly-hard-block=true}): crossing it throws {@link
 *       AiCostBudgetExceededException} — the runaway-spend kill switch. It has no {@code userId}.
 *   <li><b>DAILY_USER</b> — a single user's spend over the rolling daily window. Soft by default
 *       ({@code mealprep.ai.budget.daily-hard-block=false}): crossing it publishes a {@link
 *       CostBudgetExceededEvent} and logs, but the call still proceeds. Configure hard to turn it
 *       into a per-user block.
 * </ul>
 *
 * <p>The monthly scope is checked first because it is the hard, system-protecting limit; a
 * system-wide breach short-circuits before the per-user evaluation. Soft breaches publish a {@code
 * CostBudgetExceededEvent} from here (Flow 1 step 5: "the user is always alerted on a breach");
 * hard breaches throw, and the dispatcher ({@link AiServiceImpl}) owns the audit-row update +
 * hard-breach event so a rejection still appears in the call log.
 *
 * <p>{@code enabled=false} short-circuits the check entirely (dev / test convenience). The
 * concurrency race window is acknowledged by {@code lld/ai.md} §Concurrency: a couple of
 * stale-allowed calls is acceptable given graceful-degrade semantics.
 */
@Component
public class CostBudgetGuard {

  /** Pence per micropence — used to convert the ledger's integer micropence back to pence. */
  private static final BigDecimal MICRO_PER_PENCE = BigDecimal.valueOf(1_000_000L);

  private final AiCallLogRepository repository;
  private final CostCalculator costCalculator;
  private final AiProperties properties;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;

  public CostBudgetGuard(
      AiCallLogRepository repository,
      CostCalculator costCalculator,
      AiProperties properties,
      Clock clock,
      ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.costCalculator = costCalculator;
    this.properties = properties;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Evaluate both budget scopes for the in-flight call. Throws {@link
   * AiCostBudgetExceededException} on a <em>hard</em> breach (monthly by default; daily if
   * configured hard). On a <em>soft</em> breach the call proceeds, but a {@link
   * CostBudgetExceededEvent} is published first so the user / ops are alerted.
   *
   * <p>System-initiated tasks (no {@code userId}) skip the per-user DAILY_USER scope — there is
   * nobody to bill — but are still subject to the system-wide MONTHLY_TOTAL scope.
   *
   * @throws AiCostBudgetExceededException when a hard cap is reached
   */
  public void checkOrThrow(AiTask<?> task) {
    AiProperties.Budget budget = properties.budget();
    if (!Boolean.TRUE.equals(budget.enabled())) {
      return;
    }
    Instant now = Instant.now(clock);
    long estimateMicro = estimateMicroFor(task);

    // 1) MONTHLY_TOTAL — system-wide hard ceiling, checked first.
    checkMonthly(budget, estimateMicro, now);

    // 2) DAILY_USER — per-user scope; soft by default.
    UUID userId = task.userId().orElse(null);
    if (userId != null) {
      checkDailyUser(budget, userId, estimateMicro, now);
    }
  }

  private void checkMonthly(AiProperties.Budget budget, long estimateMicro, Instant now) {
    Duration window = budget.monthlyWindow();
    Instant since = now.minus(window);
    long spentMicro = repository.sumCostMicroPenceGlobalSince(since);
    long limitMicro = penceToMicro(budget.monthlyPenceTotal());
    if (spentMicro + estimateMicro < limitMicro) {
      return;
    }
    BigDecimal spentPence = microPenceToPence(spentMicro);
    BigDecimal limitPence = microPenceToPence(limitMicro);
    if (Boolean.TRUE.equals(budget.monthlyHardBlock())) {
      // Hard: reject. AiServiceImpl records the FAILED row and publishes the hard-breach event.
      throw new AiCostBudgetExceededException(
          null,
          BudgetScope.MONTHLY_TOTAL,
          spentPence,
          limitPence,
          window,
          clampToOneSecond(window));
    }
    // Soft monthly: alert-and-proceed.
    publishSoftBreach(null, BudgetScope.MONTHLY_TOTAL, spentPence, limitPence, window, now);
  }

  private void checkDailyUser(
      AiProperties.Budget budget, UUID userId, long estimateMicro, Instant now) {
    Duration window = budget.window();
    Instant since = now.minus(window);
    long spentMicro = repository.sumCostMicroPenceForUserSince(userId, since);
    long limitMicro = penceToMicro(budget.dailyPencePerUser());
    if (spentMicro + estimateMicro < limitMicro) {
      return;
    }
    BigDecimal spentPence = microPenceToPence(spentMicro);
    BigDecimal limitPence = microPenceToPence(limitMicro);
    if (Boolean.TRUE.equals(budget.dailyHardBlock())) {
      Duration retryAfter = retryAfterFor(userId, since, window, now);
      throw new AiCostBudgetExceededException(
          userId, BudgetScope.DAILY_USER, spentPence, limitPence, window, retryAfter);
    }
    // Soft daily (default): alert-and-proceed.
    publishSoftBreach(userId, BudgetScope.DAILY_USER, spentPence, limitPence, window, now);
  }

  private void publishSoftBreach(
      UUID userId,
      BudgetScope scope,
      BigDecimal spentPence,
      BigDecimal limitPence,
      Duration window,
      Instant now) {
    eventPublisher.publishEvent(
        new CostBudgetExceededEvent(
            userId, scope, false, spentPence, limitPence, window, UUID.randomUUID(), now));
  }

  /**
   * Coarse upper-bound estimate for the in-flight call. We don't know token counts at pre-check
   * time; a fixed prompt+response budget per tier keeps the math simple. Precision lands when the
   * actual call's cost is logged.
   */
  long estimateMicroFor(AiTask<?> task) {
    ModelTier tier = task.tier();
    return costCalculator.estimate(tier, estimatedRequestTokens(tier), estimatedResponseTokens());
  }

  private static int estimatedRequestTokens(ModelTier tier) {
    // Coarse: a reasonable upper bound for the prompts in this codebase. Higher tiers tend to get
    // larger contexts, but the cap exists to surface runaway spend, not to model the median call.
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

  private static long penceToMicro(long pence) {
    return Math.multiplyExact(pence, 1_000_000L);
  }

  /**
   * Time until the oldest SUCCEEDED row in the per-user window exits, floored to one second. When
   * no rows exist (estimate alone tripped the cap — unusual but possible if the cap is set very
   * low), defer to the configured window length.
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
