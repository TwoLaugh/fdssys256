package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.example.mealprep.testsupport.TestContainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for the cost-budget guard. Boots the full Spring context (Testcontainers
 * Postgres) and exercises:
 *
 * <ul>
 *   <li>FAILED rows do not count toward spend;
 *   <li>SUCCEEDED rows do, and the rolling-window sum is correct;
 *   <li>{@code budget.enabled=false} short-circuits without querying the DB;
 *   <li>Crossing the cap throws {@link AiCostBudgetExceededException}.
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "mealprep.ai.budget.enabled=true",
      "mealprep.ai.budget.daily-pence-per-user=50",
      "mealprep.ai.budget.window-hours=24",
      // Daily made HARD here so the IT can assert a real per-user rejection against Postgres.
      "mealprep.ai.budget.daily-hard-block=true",
      // High monthly ceiling so the daily-scope tests aren't masked by the system-wide cap.
      "mealprep.ai.budget.monthly-pence-total=1000000",
      "mealprep.ai.budget.monthly-window-hours=720",
      "mealprep.ai.budget.monthly-hard-block=true"
    })
class CostBudgetIT {

  @Autowired private AiCallLogRepository repository;
  @Autowired private AiProperties properties;
  @Autowired private CostCalculator costCalculator;
  @Autowired private Clock clock;
  @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @PersistenceContext private EntityManager entityManager;

  @AfterEach
  void cleanup() {
    repository.deleteAll();
  }

  @Test
  void failedRows_doNotCountTowardBudget_succeededRowsDo() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now(clock);
    persist(userId, now.minus(Duration.ofMinutes(30)), CallStatus.FAILED, 60_000_000L);
    persist(userId, now.minus(Duration.ofMinutes(15)), CallStatus.SUCCEEDED, 10_000_000L);

    long sum = repository.sumCostMicroPenceForUserSince(userId, now.minus(Duration.ofHours(24)));
    assertThat(sum).isEqualTo(10_000_000L);
  }

  @Test
  void rolling_window_excludesOldRows() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now(clock);
    persist(userId, now.minus(Duration.ofHours(25)), CallStatus.SUCCEEDED, 40_000_000L);
    persist(userId, now.minus(Duration.ofHours(1)), CallStatus.SUCCEEDED, 5_000_000L);

    long sum = repository.sumCostMicroPenceForUserSince(userId, now.minus(Duration.ofHours(24)));
    assertThat(sum).isEqualTo(5_000_000L);
  }

  @Test
  void budgetGuard_disabled_doesNotThrow() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now(clock);
    persist(userId, now.minus(Duration.ofMinutes(10)), CallStatus.SUCCEEDED, 100_000_000L);

    AiProperties disabled =
        new AiProperties(
            properties.anthropicApiKey(),
            properties.anthropicBaseUrl(),
            properties.tierCheapModel(),
            properties.tierMidModel(),
            properties.tierHighModel(),
            properties.timeoutSeconds(),
            properties.maxRetries(),
            properties.openaiApiKey(),
            properties.embedding(),
            AiProperties.Budget.ofDaily(false, 50L, 24));
    CostBudgetGuard guard =
        new CostBudgetGuard(repository, costCalculator, disabled, clock, eventPublisher);
    AiTask<String> task =
        AiTestData.task(String.class).withTier(ModelTier.CHEAP).withUserId(userId).build();

    guard.checkOrThrow(task);
  }

  @Test
  void dailyUser_hardBlock_overLimit_throws_andEvictionTimeMatchesOldestRow() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now(clock);
    persist(userId, now.minus(Duration.ofHours(20)), CallStatus.SUCCEEDED, 30_000_000L);
    persist(userId, now.minus(Duration.ofMinutes(30)), CallStatus.SUCCEEDED, 30_000_000L);

    CostBudgetGuard guard =
        new CostBudgetGuard(repository, costCalculator, properties, clock, eventPublisher);
    AiTask<String> task =
        AiTestData.task(String.class).withTier(ModelTier.CHEAP).withUserId(userId).build();

    assertThatThrownBy(() -> guard.checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              AiCostBudgetExceededException budget = (AiCostBudgetExceededException) ex;
              assertThat(budget.scope())
                  .isEqualTo(com.example.mealprep.ai.event.BudgetScope.DAILY_USER);
              assertThat(budget.userId()).isEqualTo(userId);
              // Oldest row at 20h → ~4h until eviction (24h window).
              assertThat(budget.retryAfter().toHours()).isBetween(3L, 5L);
            });
  }

  @Test
  void monthlyTotal_hardBlock_systemWideOverLimit_throws_withSystemScope() {
    // Two different users contribute to the system-wide monthly spend; the cap is global.
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    Instant now = Instant.now(clock);
    // 6_000_000 micropence = £60; two rows = £120 system-wide.
    persist(userA, now.minus(Duration.ofDays(5)), CallStatus.SUCCEEDED, 60_000_000L);
    persist(userB, now.minus(Duration.ofDays(2)), CallStatus.SUCCEEDED, 60_000_000L);

    // Monthly cap £1.00 (100 pence) so the seeded £120 trips it; daily made effectively
    // unreachable.
    AiProperties tightMonthly =
        new AiProperties(
            properties.anthropicApiKey(),
            properties.anthropicBaseUrl(),
            properties.tierCheapModel(),
            properties.tierMidModel(),
            properties.tierHighModel(),
            properties.timeoutSeconds(),
            properties.maxRetries(),
            properties.openaiApiKey(),
            properties.embedding(),
            new AiProperties.Budget(true, 100_000L, 24, false, 100L, 720, true));
    CostBudgetGuard guard =
        new CostBudgetGuard(repository, costCalculator, tightMonthly, clock, eventPublisher);
    AiTask<String> task =
        AiTestData.task(String.class).withTier(ModelTier.CHEAP).withUserId(userA).build();

    assertThatThrownBy(() -> guard.checkOrThrow(task))
        .isInstanceOf(AiCostBudgetExceededException.class)
        .satisfies(
            ex -> {
              AiCostBudgetExceededException budget = (AiCostBudgetExceededException) ex;
              assertThat(budget.scope())
                  .isEqualTo(com.example.mealprep.ai.event.BudgetScope.MONTHLY_TOTAL);
              assertThat(budget.userId()).isNull();
            });
  }

  /**
   * Insert a row, then OVERRIDE {@code created_at} via a native update so the rolling-window logic
   * is deterministic. Hibernate's {@code @CreationTimestamp} sets {@code created_at} to "now" on
   * insert; without this override the test couldn't simulate "20 hours ago" rows. Each insert runs
   * in its own transaction so the assertions further down see committed data.
   */
  private void persist(UUID userId, Instant createdAt, CallStatus status, long costMicroPence) {
    UUID id = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              AiCallLog row =
                  new AiCallLog(
                      id,
                      userId,
                      null,
                      TaskType.FEEDBACK_CLASSIFICATION,
                      ModelTier.CHEAP,
                      "haiku-id",
                      "test/echo",
                      1,
                      status);
              row.setCostMicroPence(costMicroPence);
              if (status == CallStatus.SUCCEEDED) {
                row.setCompletedAt(createdAt);
                row.setRequestTokens(100);
                row.setResponseTokens(50);
                row.setLatencyMs(123);
              } else {
                row.setLatencyMs(456);
              }
              repository.saveAndFlush(row);
              entityManager
                  .createNativeQuery("UPDATE ai_call_log SET created_at = ?1 WHERE id = ?2")
                  .setParameter(1, Timestamp.from(createdAt))
                  .setParameter(2, id)
                  .executeUpdate();
            });
  }
}
