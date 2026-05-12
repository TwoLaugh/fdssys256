package com.example.mealprep.provisions.domain.service.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Dormant SPI listener for the future grocery module's {@code GroceryOrderConfirmedEvent}. The
 * grocery module doesn't exist yet — the {@code @ConditionalOnClass} keeps this bean unregistered
 * until the grocery module ships its event record. Until then, callers invoke {@code
 * applyGroceryOrder} directly via the {@code ProvisionUpdateService} facade (or the REST endpoint
 * {@code POST /api/v1/provisions/grocery-import}).
 *
 * <p>The class-name {@code @ConditionalOnClass} uses the string form (NOT the class-literal form)
 * so that the listener compiles even with the grocery module absent from the classpath. The body is
 * empty — when the grocery module ships, this class gains an {@code @TransactionalEventListener
 * (AFTER_COMMIT)} + {@code @Transactional(propagation = REQUIRES_NEW)} method per the LLD §Flow 2
 * spec, and the {@code @ConditionalOnClass} flips to active.
 *
 * <p>Round-7 lesson encoded: when the listener body lands, it MUST carry {@code @Transactional(
 * propagation = Propagation.REQUIRES_NEW)} alongside {@code @TransactionalEventListener(
 * AFTER_COMMIT)} because the listener calls {@code applyGroceryOrder} which is itself
 * {@code @Transactional} — REQUIRED would join an absent transaction; REQUIRES_NEW (or
 * NOT_SUPPORTED) is the only safe propagation.
 */
@Component
@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")
class GroceryOrderConfirmedListener {
  // Intentionally empty: see class-level Javadoc. The grocery module ships its
  // GroceryOrderConfirmedEvent record + this listener gains its @TransactionalEventListener method
  // in a follow-up.
}
