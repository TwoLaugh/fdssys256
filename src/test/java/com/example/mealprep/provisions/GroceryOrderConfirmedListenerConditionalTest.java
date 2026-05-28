package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Verifies the {@code GroceryOrderConfirmedListener}'s {@code @ConditionalOnClass} contract. We
 * assert two complementary facts without booting a full Spring context (which would require Docker
 * via Testcontainers in the IT path):
 *
 * <ul>
 *   <li>The listener class is annotated {@code @ConditionalOnClass(name =
 *       "...GroceryOrderConfirmedEvent")} — the string-form variant (NOT class-literal) ensures it
 *       compiles regardless of whether the grocery module is present.
 *   <li>{@code com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent} is NOW present on the
 *       classpath (shipped by grocery-01e), so Spring's auto-configuration evaluator REGISTERS the
 *       listener bean — the dormant state has flipped to active. This is the expected cross-module
 *       consequence of the grocery module shipping its event record; the listener body itself
 *       remains a no-op until provisions wires its {@code @TransactionalEventListener} method in a
 *       provisions follow-up (the body is empty, so registration is harmless at context load).
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
  void groceryEventClass_isPresentOnClasspathNow() {
    boolean present;
    try {
      Class.forName("com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent");
      present = true;
    } catch (ClassNotFoundException e) {
      present = false;
    }
    assertThat(present)
        .as(
            "grocery-01e ships GroceryOrderConfirmedEvent — the @ConditionalOnClass now evaluates"
                + " true and the (no-op) listener bean registers.")
        .isTrue();
  }
}
