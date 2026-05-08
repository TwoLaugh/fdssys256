package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.internal.CostBudgetGuard;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CostBudgetGuard}. Repository is mocked because the budget arithmetic is a
 * pure decision over a single sum query plus an oldest-row lookup; Postgres semantics are exercised
 * in {@code CostBudgetIT}.
 */
@ExtendWith(MockitoExtension.class)
class CostBudgetGuardTest {

  @Mock private AiCallLogRepository repository;

  private final CostCalculator calculator = new CostCalculator();
  private final Instant now = Instant.parse("2026-05-08T12:00:00Z");
  private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

  /** Default-budget properties with explicit values so the test isn't sensitive to defaults. */
  private AiProperties propsWith(boolean enabled, long dailyPence, int windowHours) {
    return new AiProperties(
        "k",
        null,
        "haiku-id",
        "sonnet-id",
        "opus-id",
        60,
        3,
        null,
        null,
        new AiProperties.Budget(enabled, dailyPence, windowHours));
  }

  private CostBudgetGuard guard(AiProperties props) {
    return new CostBudgetGuard(repository, calculator, props, fixedClock);
  }

  private AiTask<String> taskFor(UUID userId, ModelTier tier) {
    return AiTestData.task(String.class)
        .ofType(TaskType.FEEDBACK_CLASSIFICATION)
        .withTier(tier)
        .withUserId(userId)
        .build();
  }

  @Test
  void disabled_shortCircuits_neverQueriesDb() {
    AiTask<String> task = taskFor(UUID.randomUUID(), ModelTier.CHEAP);
    AiProperties props = propsWith(false, 50L, 24);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
  }

  @Test
  void systemInitiated_noUserId_isExempt() {
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    AiProperties props = propsWith(true, 50L, 24);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
  }

  @Test
  void firstTimeUser_zeroSpend_passesWhenEstimateBelowLimit() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(0L);
    // 50p limit, CHEAP estimate: 4000*79 + 2000*395 = 316_000 + 790_000 = 1_106_000 micropence
    // = 1.106 pence — well under 50p. Should pass.
    AiProperties props = propsWith(true, 50L, 24);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
  }

  @Test
  void belowLimit_passes() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    // 30p already spent, estimate ~1.1p, limit 50p → 30 + 1.1 < 50 → pass.
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(30_000_000L);
    AiProperties props = propsWith(true, 50L, 24);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
  }

  @Test
  void exactlyAtLimit_isRejected_limitIsExclusive() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    // 50p already spent, 50p limit — even with zero estimate this is at the cap.
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(50_000_000L);
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(now.minus(Duration.ofHours(1)))));
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class);
  }

  @Test
  void aboveLimit_isRejected() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(100_000_000L);
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(now.minus(Duration.ofHours(2)))));
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              AiCostBudgetExceededException budget = (AiCostBudgetExceededException) ex;
              assertThat(budget.userId()).isEqualTo(userId);
              assertThat(budget.spentPence()).isEqualByComparingTo(new BigDecimal("100.00"));
              assertThat(budget.limitPence()).isEqualByComparingTo(new BigDecimal("50.00"));
            });
  }

  @Test
  void retryAfter_basedOnOldestRow_inWindow() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(100_000_000L);
    // Oldest row 23 hours ago; 24h window → ~1h until release.
    Instant oldest = now.minus(Duration.ofHours(23));
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(oldest)));
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              Duration retryAfter = ((AiCostBudgetExceededException) ex).retryAfter();
              // Within ±1s rounding of 1 hour.
              assertThat(retryAfter.toSeconds()).isBetween(3_599L, 3_601L);
            });
  }

  @Test
  void retryAfter_neverZero_evenWhenOldestJustExpired() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(100_000_000L);
    Instant boundary = now.minus(Duration.ofHours(24));
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(boundary)));
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              Duration retryAfter = ((AiCostBudgetExceededException) ex).retryAfter();
              assertThat(retryAfter.toSeconds()).isGreaterThanOrEqualTo(1L);
            });
  }

  @Test
  void retryAfter_noSucceededRowsInWindow_defersToWindow() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.HIGH);
    // Sum returns >0 (perhaps a race) but findSucceeded comes back empty — defensive path.
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(60_000_000L);
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of());
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              Duration retryAfter = ((AiCostBudgetExceededException) ex).retryAfter();
              assertThat(retryAfter).isEqualTo(Duration.ofHours(24));
            });
  }

  @Test
  void estimate_pushesUserOverLimit_isRejected() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.HIGH);
    // HIGH estimate: 16000*1185 + 2000*5925 = 18_960_000 + 11_850_000 = 30_810_000 micropence
    // = 30.81p. Spent 25p → 25 + 30.81 = 55.81 > 50 → reject.
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(25_000_000L);
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(now.minus(Duration.ofHours(1)))));
    AiProperties props = propsWith(true, 50L, 24);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class);
  }

  /** Build a SUCCEEDED-status AiCallLog with the given created_at via reflection. */
  private static AiCallLog succeededRowAt(Instant createdAt) {
    AiCallLog row =
        new AiCallLog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku-id",
            "test/echo",
            1,
            CallStatus.SUCCEEDED);
    try {
      Field f = AiCallLog.class.getDeclaredField("createdAt");
      f.setAccessible(true);
      f.set(row, createdAt);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return row;
  }
}
