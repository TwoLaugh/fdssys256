package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Verifies the {@code CookEventListener} bean would be DORMANT when the planner module isn't on the
 * classpath. We assert two complementary facts without booting a full Spring context (which would
 * require Docker via Testcontainers in the IT path):
 *
 * <ul>
 *   <li>The listener class is annotated {@code @ConditionalOnClass(name = "...MealCookedEvent")} —
 *       the string-form variant (NOT class-literal) ensures it compiles without the planner module.
 *   <li>{@code com.example.mealprep.planner.event.MealCookedEvent} is genuinely absent from the
 *       classpath today, so Spring's auto-configuration evaluator will skip the listener bean.
 * </ul>
 */
class CookEventListenerConditionalTest {

  @Test
  void cookEventListener_hasConditionalOnClassPointingAtPlannerEvent() throws Exception {
    Class<?> listener =
        Class.forName("com.example.mealprep.provisions.domain.service.internal.CookEventListener");
    ConditionalOnClass condition = listener.getAnnotation(ConditionalOnClass.class);
    assertThat(condition).as("listener must carry @ConditionalOnClass").isNotNull();
    assertThat(condition.name()).contains("com.example.mealprep.planner.event.MealCookedEvent");
  }

  @Test
  void plannerEventClass_isAbsentFromClasspathToday() {
    boolean present;
    try {
      Class.forName("com.example.mealprep.planner.event.MealCookedEvent");
      present = true;
    } catch (ClassNotFoundException e) {
      present = false;
    }
    assertThat(present)
        .as(
            "planner module hasn't shipped yet — the @ConditionalOnClass evaluates false and the"
                + " listener bean is dormant.")
        .isFalse();
  }
}
