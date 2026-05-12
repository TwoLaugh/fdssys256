package com.example.mealprep.provisions.domain.service.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Dormant SPI listener for the future planner module's {@code MealCookedEvent}. The planner module
 * doesn't exist yet — the {@code @ConditionalOnClass} keeps this bean unregistered until the
 * planner ships its event record. Until then, callers invoke {@code applyCookEvent} directly via
 * the {@code ProvisionUpdateService} facade.
 *
 * <p>The class-name {@code @ConditionalOnClass} uses the string form (NOT the class-literal form)
 * so that the listener compiles even with the planner absent from the classpath. The body is empty
 * — when the planner module ships, this class gains an {@code @TransactionalEventListener} method
 * per the LLD §Flow 1 spec and the {@code @ConditionalOnClass} flips to active.
 */
@Component
@ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")
class CookEventListener {
  // Intentionally empty: see class-level Javadoc. The planner ships its MealCookedEvent
  // record + this listener gains its @TransactionalEventListener method in a follow-up.
}
