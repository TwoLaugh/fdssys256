package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@code PlanGenerationCounter} (package-private). Verifies the repository
 * fan-in (no N+1 — exactly one COUNT, exactly one SELECT) and the {@code Optional} mapping for the
 * {@code currentActivePlanIdFor} contract.
 *
 * <p>Reflection is used to instantiate the package-private {@code PlanGenerationCounter} from this
 * test (which lives in a different package); the production code is package-private intentionally.
 */
@ExtendWith(MockitoExtension.class)
class PlanGenerationCounterTest {

  @Mock private PlanRepository planRepository;

  private Object counter;

  private Object newCounter() throws Exception {
    Class<?> clazz =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.lifecycle.PlanGenerationCounter");
    Constructor<?> ctor = clazz.getDeclaredConstructor(PlanRepository.class);
    ctor.setAccessible(true);
    return ctor.newInstance(planRepository);
  }

  @SuppressWarnings("unchecked")
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

  @Test
  void nextGenerationFor_noPlansYet_returns1() throws Exception {
    counter = newCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, week)).thenReturn(0);

    assertThat(nextGenerationFor(household, week)).isEqualTo(1);

    verify(planRepository).countByHouseholdIdAndWeekStartDate(household, week);
    verifyNoMoreInteractions(planRepository);
  }

  @Test
  void nextGenerationFor_twoExistingPlans_returns3() throws Exception {
    counter = newCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, week)).thenReturn(2);

    assertThat(nextGenerationFor(household, week)).isEqualTo(3);
  }

  @Test
  void currentActivePlanIdFor_activePlanPresent_returnsItsId() throws Exception {
    counter = newCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan active = PlanTestData.testActivePlan(household, week, 1);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, week, PlanStatus.ACTIVE))
        .thenReturn(Optional.of(active));

    assertThat(currentActivePlanIdFor(household, week)).contains(active.getId());

    verify(planRepository)
        .findFirstByHouseholdIdAndWeekStartDateAndStatus(household, week, PlanStatus.ACTIVE);
    verifyNoMoreInteractions(planRepository);
  }

  @Test
  void currentActivePlanIdFor_noActivePlan_returnsEmpty() throws Exception {
    counter = newCounter();
    UUID household = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            eq(household), eq(week), any(PlanStatus.class)))
        .thenReturn(Optional.empty());

    assertThat(currentActivePlanIdFor(household, week)).isEmpty();
  }
}
