package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for {@code PlanGenerationCounter} against a real Postgres via Testcontainers.
 * Verifies the {@code count} and {@code findFirstBy...Status} repository methods behave as the
 * counter expects when actual rows exist.
 *
 * <p>The counter class itself is package-private, so we resolve it from the Spring context as
 * {@code Object} and call its methods reflectively — mirrors {@link PlanGenerationCounterTest}.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PlanGenerationCounterIT {

  @Autowired private ApplicationContext applicationContext;
  @Autowired private PlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private Object counter;

  private Object resolveCounter() {
    try {
      Class<?> clazz =
          Class.forName(
              "com.example.mealprep.planner.domain.service.internal.lifecycle"
                  + ".PlanGenerationCounter");
      return applicationContext.getBean(clazz);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private int nextGenerationFor(UUID householdId, LocalDate weekStartDate) {
    try {
      java.lang.reflect.Method m =
          counter.getClass().getDeclaredMethod("nextGenerationFor", UUID.class, LocalDate.class);
      m.setAccessible(true);
      return (Integer) m.invoke(counter, householdId, weekStartDate);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Optional<UUID> currentActivePlanIdFor(UUID householdId, LocalDate weekStartDate) {
    try {
      java.lang.reflect.Method m =
          counter
              .getClass()
              .getDeclaredMethod("currentActivePlanIdFor", UUID.class, LocalDate.class);
      m.setAccessible(true);
      return (Optional<UUID>) m.invoke(counter, householdId, weekStartDate);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private TransactionTemplate txTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
  }

  @Test
  void nextGenerationFor_firstPlanForScope_returns1() {
    counter = resolveCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    assertThat(nextGenerationFor(household, week)).isEqualTo(1);
  }

  @Test
  void nextGenerationFor_afterTwoPlansPersisted_returns3() {
    counter = resolveCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    Plan first = PlanTestData.testGeneratedPlan(household, week, 1);
    Plan second = PlanTestData.testGeneratedPlan(household, week, 2);
    txTemplate()
        .executeWithoutResult(
            tx -> {
              planRepository.save(first);
              planRepository.save(second);
            });

    assertThat(nextGenerationFor(household, week)).isEqualTo(3);
  }

  @Test
  void currentActivePlanIdFor_onlyActivePresent_returnsItsId() {
    counter = resolveCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    Plan active = PlanTestData.testActivePlan(household, week, 1);
    txTemplate().executeWithoutResult(tx -> planRepository.save(active));

    assertThat(currentActivePlanIdFor(household, week)).contains(active.getId());
  }

  @Test
  void currentActivePlanIdFor_onlyGeneratedPresent_returnsEmpty() {
    counter = resolveCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    Plan generated = PlanTestData.testGeneratedPlan(household, week, 1);
    txTemplate().executeWithoutResult(tx -> planRepository.save(generated));

    assertThat(currentActivePlanIdFor(household, week)).isEmpty();
  }

  @Test
  void currentActivePlanIdFor_onlyTerminalPlansPresent_returnsEmpty() {
    counter = resolveCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);

    Plan rejected = PlanTestData.testGeneratedPlan(household, week, 1);
    rejected.setStatus(PlanStatus.REJECTED);
    Plan abandoned = PlanTestData.testGeneratedPlan(household, week, 2);
    abandoned.setStatus(PlanStatus.ABANDONED);
    txTemplate()
        .executeWithoutResult(
            tx -> {
              planRepository.save(rejected);
              planRepository.save(abandoned);
            });

    assertThat(currentActivePlanIdFor(household, week)).isEmpty();
  }
}
