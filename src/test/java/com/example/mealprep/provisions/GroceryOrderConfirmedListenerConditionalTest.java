package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Verifies the {@code GroceryOrderConfirmedListener} bean would be DORMANT when the grocery module
 * isn't on the classpath. We assert two complementary facts without booting a full Spring context
 * (which would require Docker via Testcontainers in the IT path):
 *
 * <ul>
 *   <li>The listener class is annotated {@code @ConditionalOnClass(name =
 *       "...GroceryOrderConfirmedEvent")} — the string-form variant (NOT class-literal) ensures it
 *       compiles without the grocery module.
 *   <li>{@code com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent} is genuinely absent
 *       from the classpath today, so Spring's auto-configuration evaluator will skip the listener
 *       bean.
 * </ul>
 */
class GroceryOrderConfirmedListenerConditionalTest {

  @Test
  void listener_hasConditionalOnClassPointingAtGroceryEvent() throws Exception {
    Class<?> listener =
        Class.forName(
            "com.example.mealprep.provisions.domain.service.internal.GroceryOrderConfirmedListener");
    ConditionalOnClass condition = listener.getAnnotation(ConditionalOnClass.class);
    assertThat(condition).as("listener must carry @ConditionalOnClass").isNotNull();
    assertThat(condition.name())
        .contains("com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent");
  }

  @Test
  void groceryEventClass_isAbsentFromClasspathToday() {
    boolean present;
    try {
      Class.forName("com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent");
      present = true;
    } catch (ClassNotFoundException e) {
      present = false;
    }
    assertThat(present)
        .as(
            "grocery module hasn't shipped yet — the @ConditionalOnClass evaluates false and the"
                + " listener bean is dormant.")
        .isFalse();
  }
}
