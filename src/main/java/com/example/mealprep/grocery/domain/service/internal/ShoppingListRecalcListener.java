package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.provisions.event.ProvisionChangedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tier-1 recalculation triggers (grocery-01b). Per lld/grocery.md §Events §Consumed lines 841-852.
 *
 * <ul>
 *   <li>{@code onPlanGenerated} — recalculate the shopping list for the newly-generated {@code
 *       (planId, generation)}.
 *   <li>{@code onProvisionChanged} — the {@code ProvisionChangedEvent} hierarchy is sealed; only
 *       {@link ItemSpoiledEvent} / {@link ItemRanOutEvent} (inventory shrank) may invalidate the
 *       active list. {@code ItemAddedFromGroceryEvent} + {@code SubstitutionAcceptedEvent} (Tier 3
 *       already wrote them) and the other sub-kinds do NOT trigger a recalculate. A single grocery
 *       delivery batches many item updates, so recalculation is <b>debounced 5 s per {@code
 *       (userId, planId)}</b> — 15 item updates collapse to one recalculate.
 * </ul>
 *
 * <p><b>Round-7 listener rule (non-negotiable):</b>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Transactional(REQUIRES_NEW)} — the
 * listener body does JPA work and calls {@code recalculate} (itself {@code @Transactional}); plain
 * {@code REQUIRED} would join an absent transaction.
 *
 * <p><b>Debounce mechanism (worth-user-review resolved):</b> a singleton-held {@code
 * ConcurrentHashMap<DebounceKey, Instant>} of coalescing windows, drained by a {@code @Scheduled}
 * sweep. Each inventory-drift event (re)sets the key's due time to {@code now + 5 s}; the sweep
 * fires the recalculate once the window elapses with no further event. {@code recalculate} runs in
 * its OWN transaction via the {@link ShoppingListService} bean (a distinct Spring proxy — no
 * self-invocation pitfall). The provisions module's {@code ProvisionEventBatcher} is a
 * per-transaction batcher, NOT a cross-event time window, so this new mechanism is required.
 *
 * <p>Package-private {@code @Component}; cross-module isolation is asserted by {@code
 * GroceryBoundaryTest}.
 */
@Component
class ShoppingListRecalcListener {

  private static final Logger log = LoggerFactory.getLogger(ShoppingListRecalcListener.class);

  /** The 5-second coalescing window per (userId, planId) — LLD line 852. */
  static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(5);

  private final ShoppingListService shoppingListService;
  private final ShoppingListDataGateway shoppingListDataGateway;
  private final HouseholdQueryService householdQueryService;
  private final Clock clock;

  /** (userId, planId) → the instant at which the coalesced recalculate becomes due. */
  private final Map<DebounceKey, Instant> pending = new ConcurrentHashMap<>();

  ShoppingListRecalcListener(
      ShoppingListService shoppingListService,
      ShoppingListDataGateway shoppingListDataGateway,
      HouseholdQueryService householdQueryService,
      Clock clock) {
    this.shoppingListService = shoppingListService;
    this.shoppingListDataGateway = shoppingListDataGateway;
    this.householdQueryService = householdQueryService;
    this.clock = clock;
  }

  /** A plan was (re)generated → recalculate that plan's shopping list immediately. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onPlanGenerated(PlanGeneratedEvent event) {
    try {
      UUID userId = resolveUserForHousehold(event.householdId());
      if (userId == null) {
        log.debug("PlanGeneratedEvent household {} maps to no user; skipping", event.householdId());
        return;
      }
      shoppingListService.recalculate(
          userId, new RecalculateShoppingListRequest(event.planId(), event.generation()));
      log.info(
          "onPlanGenerated recalculated shopping list plan={} generation={}",
          event.planId(),
          event.generation());
    } catch (RuntimeException ex) {
      // AFTER_COMMIT: the publisher's tx is already committed; never re-throw (round-7 rule).
      log.warn("onPlanGenerated failed for plan {}: {}", event.planId(), ex.toString(), ex);
    }
  }

  /** Inventory drift → schedule a debounced recalculate of the user's active list(s). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onProvisionChanged(ProvisionChangedEvent event) {
    if (!triggersRecalc(event)) {
      log.debug(
          "onProvisionChanged {} does not invalidate the list; ignored",
          event.getClass().getSimpleName());
      return;
    }
    UUID userId = event.userId();
    if (userId == null) {
      return;
    }
    Instant due = clock.instant().plus(DEBOUNCE_WINDOW);
    int scheduled = 0;
    for (ShoppingList list : shoppingListDataGateway.findActiveByUserId(userId)) {
      pending.put(new DebounceKey(userId, list.getPlanId()), due);
      scheduled++;
    }
    log.debug(
        "onProvisionChanged {} debounced recalc for user={} activeLists={}",
        event.getClass().getSimpleName(),
        userId,
        scheduled);
  }

  /**
   * Drain elapsed debounce windows. Runs every second; recalculates each {@code (userId, planId)}
   * whose window has passed. Each recalculate runs in its own transaction via the {@link
   * ShoppingListService} proxy. Idempotent: a same-generation recalculate returns the existing row.
   */
  @Scheduled(fixedDelayString = "${mealprep.grocery.recalc-debounce-sweep-ms:1000}")
  void drainDebouncedRecalculations() {
    if (pending.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    for (Map.Entry<DebounceKey, Instant> entry : pending.entrySet()) {
      if (entry.getValue().isAfter(now)) {
        continue; // window not yet elapsed
      }
      DebounceKey key = entry.getKey();
      // Remove only if the due-time hasn't advanced (a new event during the sweep re-arms it).
      if (!pending.remove(key, entry.getValue())) {
        continue;
      }
      try {
        shoppingListService.recalculate(
            key.userId(), new RecalculateShoppingListRequest(key.planId(), null));
        log.info("debounced recalculate fired for user={} plan={}", key.userId(), key.planId());
      } catch (RuntimeException ex) {
        log.warn(
            "debounced recalculate failed for user={} plan={}: {}",
            key.userId(),
            key.planId(),
            ex.toString());
      }
    }
  }

  /** Only inventory-shrinking sub-kinds invalidate the active list (LLD line 846/852). */
  private static boolean triggersRecalc(ProvisionChangedEvent event) {
    return event instanceof ItemSpoiledEvent || event instanceof ItemRanOutEvent;
  }

  /** Resolve a representative user for the household (the primary/first member), or null. */
  private UUID resolveUserForHousehold(UUID householdId) {
    if (householdId == null) {
      return null;
    }
    return householdQueryService
        .getById(householdId)
        .map(HouseholdDto::members)
        .filter(members -> members != null && !members.isEmpty())
        .map(members -> primaryMember(members).userId())
        .orElse(null);
  }

  private static HouseholdMemberDto primaryMember(List<HouseholdMemberDto> members) {
    return members.stream()
        .max((a, b) -> Integer.compare(priority(a), priority(b)))
        .orElse(members.get(0));
  }

  private static int priority(HouseholdMemberDto m) {
    return m.priority();
  }

  /** Debounce coalescing key — the LLD's {@code (userId, planId)} pair. */
  record DebounceKey(UUID userId, UUID planId) {}
}
