package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.notification.event.StapleReplenishmentNeededEvent;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PrepReminderEvent;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import org.springframework.stereotype.Component;

/**
 * Thin public bridge the {@code notification.event} listeners inject. It pairs the package-private
 * {@code NotificationKindResolver} with the package-private {@code NotificationDispatcher} so the
 * dispatcher itself stays internal (listeners-only, per {@code lld/notification.md}). One method
 * per producer event: resolve the event to a draft, then dispatch it.
 */
@Component
public class NotificationDispatcherFacade {

  private final NotificationKindResolver resolver;
  private final NotificationDispatcher dispatcher;

  NotificationDispatcherFacade(
      NotificationKindResolver resolver, NotificationDispatcher dispatcher) {
    this.resolver = resolver;
    this.dispatcher = dispatcher;
  }

  public void dispatchItemNearingExpiry(ItemNearingExpiryEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchItemSpoiled(ItemSpoiledEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchDefrostReminder(DefrostReminderEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchNutritionIntakeDiverged(NutritionIntakeDivergedEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchHealthDirectiveReceived(HealthDirectiveReceivedEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchPrepReminder(PrepReminderEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchReoptSuggested(ReoptSuggestedEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchPlanGenerated(PlanGeneratedEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }

  public void dispatchStapleReplenishmentNeeded(StapleReplenishmentNeededEvent event) {
    dispatcher.dispatch(resolver.resolve(event));
  }
}
