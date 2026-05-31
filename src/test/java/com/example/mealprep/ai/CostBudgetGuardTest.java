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
import com.example.mealprep.ai.event.BudgetScope;
import com.example.mealprep.ai.event.CostBudgetExceededEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link CostBudgetGuard} — the two-scope cost model (lld/ai.md Flow 1 step 5 +
 * Decisions §7). Repository is mocked because the budget arithmetic is a pure decision over two sum
 * queries plus an oldest-row lookup; Postgres semantics are exercised in {@code CostBudgetIT}.
 *
 * <p><b>DAILY_USER</b> is soft by default (alert-and-proceed); <b>MONTHLY_TOTAL</b> is a hard,
 * system-wide block by default.
 */
@ExtendWith(MockitoExtension.class)
class CostBudgetGuardTest {

  @Mock private AiCallLogRepository repository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final CostCalculator calculator = new CostCalculator();
  private final Instant now = Instant.parse("2026-05-08T12:00:00Z");
  private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

  /** Full-control budget — every field explicit so the test isn't sensitive to defaults. */
  private AiProperties propsWith(
      boolean enabled,
      long dailyPence,
      int windowHours,
      boolean dailyHardBlock,
      long monthlyPence,
      int monthlyWindowHours,
      boolean monthlyHardBlock) {
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
        new AiProperties.Budget(
            enabled,
            dailyPence,
            windowHours,
            dailyHardBlock,
            monthlyPence,
            monthlyWindowHours,
            monthlyHardBlock));
  }

  /** Daily-soft defaults with a very high monthly cap so the monthly scope never trips. */
  private AiProperties dailySoft(long dailyPence) {
    return propsWith(true, dailyPence, 24, false, 10_000_000L, 24 * 30, true);
  }

  private CostBudgetGuard guard(AiProperties props) {
    return new CostBudgetGuard(repository, calculator, props, fixedClock, eventPublisher);
  }

  private AiTask<String> taskFor(UUID userId, ModelTier tier) {
    return AiTestData.task(String.class)
        .ofType(TaskType.FEEDBACK_CLASSIFICATION)
        .withTier(tier)
        .withUserId(userId)
        .build();
  }

  // ---- short-circuits ----

  @Test
  void disabled_shortCircuits_neverQueriesDb() {
    AiTask<String> task = taskFor(UUID.randomUUID(), ModelTier.CHEAP);
    AiProperties props = propsWith(false, 50L, 24, false, 20_000L, 24 * 30, true);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
    verify(repository, never()).sumCostMicroPenceGlobalSince(any());
  }

  @Test
  void systemInitiated_noUserId_skipsDailyScope_butStillChecksMonthly() {
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);

    assertThatCode(() -> guard(dailySoft(50L)).checkOrThrow(task)).doesNotThrowAnyException();
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
    verify(repository).sumCostMicroPenceGlobalSince(any());
  }

  // ---- DAILY_USER (soft by default) ----

  @Test
  void dailyUser_belowLimit_passes_noEvent() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(30_000_000L);

    assertThatCode(() -> guard(dailySoft(50L)).checkOrThrow(task)).doesNotThrowAnyException();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void dailyUser_softBreach_proceeds_butPublishesEvent() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);
    // 50p already spent on a 50p daily cap — at the (exclusive) cap. Soft → proceed + event.
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(50_000_000L);

    assertThatCode(() -> guard(dailySoft(50L)).checkOrThrow(task)).doesNotThrowAnyException();

    ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(cap.capture());
    assertThat(cap.getValue()).isInstanceOf(CostBudgetExceededEvent.class);
    CostBudgetExceededEvent ev = (CostBudgetExceededEvent) cap.getValue();
    assertThat(ev.scope()).isEqualTo(BudgetScope.DAILY_USER);
    assertThat(ev.hardBlock()).isFalse();
    assertThat(ev.userId()).isEqualTo(userId);
    assertThat(ev.spentPence()).isEqualByComparingTo(new BigDecimal("50.00"));
    assertThat(ev.limitPence()).isEqualByComparingTo(new BigDecimal("50.00"));
  }

  @Test
  void dailyUser_hardBlockConfigured_throwsWithRetryAfter() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(100_000_000L);
    // Oldest row 23h ago, 24h window → ~1h until release.
    when(repository.findSucceededForUserSinceOrderByCreatedAtAsc(eq(userId), any()))
        .thenReturn(List.of(succeededRowAt(now.minus(Duration.ofHours(23)))));
    AiProperties props = propsWith(true, 50L, 24, true, 10_000_000L, 24 * 30, true);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              AiCostBudgetExceededException b = (AiCostBudgetExceededException) ex;
              assertThat(b.scope()).isEqualTo(BudgetScope.DAILY_USER);
              assertThat(b.userId()).isEqualTo(userId);
              assertThat(b.spentPence()).isEqualByComparingTo(new BigDecimal("100.00"));
              assertThat(b.retryAfter().toSeconds()).isBetween(3_599L, 3_601L);
            });
  }

  @Test
  void dailyUser_estimatePushesOverLimit_softBreach_proceeds() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.HIGH);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(0L);
    // HIGH estimate ~30.81p; spent 25p → 25 + 30.81 > 50 → breach (soft).
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(25_000_000L);

    assertThatCode(() -> guard(dailySoft(50L)).checkOrThrow(task)).doesNotThrowAnyException();
    verify(eventPublisher).publishEvent(any(CostBudgetExceededEvent.class));
  }

  // ---- MONTHLY_TOTAL (hard by default) ----

  @Test
  void monthlyTotal_belowLimit_passes() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(100_000_000L); // £1
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(0L);
    // £200 monthly cap.
    AiProperties props = propsWith(true, 50L, 24, false, 20_000L, 24 * 30, true);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();
  }

  @Test
  void monthlyTotal_hardBreach_throws_systemScope_noUserId() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    // 200p monthly cap, already 250p spent system-wide → hard breach.
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(250_000_000L);
    AiProperties props = propsWith(true, 50L, 24, false, 200L, 24 * 30, true);

    assertThatThrownBy(() -> guard(props).checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              AiCostBudgetExceededException b = (AiCostBudgetExceededException) ex;
              assertThat(b.scope()).isEqualTo(BudgetScope.MONTHLY_TOTAL);
              assertThat(b.userId()).isNull();
              // limitPence is in pence: a 200-pence cap → 200.00; spent 250_000_000 µpence =
              // 250.00.
              assertThat(b.limitPence()).isEqualByComparingTo(new BigDecimal("200.00"));
              assertThat(b.spentPence()).isEqualByComparingTo(new BigDecimal("250.00"));
              assertThat(b.retryAfter().toSeconds()).isGreaterThanOrEqualTo(1L);
            });
    // Monthly is checked first; per-user scope never queried once monthly hard-blocks.
    verify(repository, never()).sumCostMicroPenceForUserSince(any(), any());
  }

  @Test
  void monthlyTotal_softConfigured_proceeds_butPublishesSystemEvent() {
    UUID userId = UUID.randomUUID();
    AiTask<String> task = taskFor(userId, ModelTier.CHEAP);
    when(repository.sumCostMicroPenceGlobalSince(any())).thenReturn(250_000_000L);
    when(repository.sumCostMicroPenceForUserSince(eq(userId), any())).thenReturn(0L);
    // Monthly soft, daily soft and high so only the monthly event fires.
    AiProperties props = propsWith(true, 10_000L, 24, false, 200L, 24 * 30, false);

    assertThatCode(() -> guard(props).checkOrThrow(task)).doesNotThrowAnyException();

    ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(cap.capture());
    CostBudgetExceededEvent ev = (CostBudgetExceededEvent) cap.getValue();
    assertThat(ev.scope()).isEqualTo(BudgetScope.MONTHLY_TOTAL);
    assertThat(ev.hardBlock()).isFalse();
    assertThat(ev.userId()).isNull();
    assertThat(ev.scopeId()).isEqualTo(CostBudgetExceededEvent.SYSTEM_SCOPE_ID);
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
