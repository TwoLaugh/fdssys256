package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sealed base for the provisions module's "inventory-affecting" event hierarchy. Permits the 6
 * concrete record variants enumerated in the LLD §Events block (line 560-570). Listeners that want
 * the catch-all signal subscribe on this type; listeners that care about a specific flow subscribe
 * on the concrete record.
 *
 * <p>{@link com.example.mealprep.provisions.event.InventoryItemUpsertedEvent}, {@link
 * com.example.mealprep.provisions.event.BudgetChangedEvent} and {@link
 * com.example.mealprep.provisions.event.EquipmentChangedEvent} are kept OUTSIDE the sealed
 * hierarchy — see 01g's LLD-divergence note ("Option 1 chosen"). Those events carry shapes that
 * don't fit the {@code affectedItemIds}-keyed contract; retrofitting them would change downstream
 * subscribers' contracts.
 *
 * <p>The base extends {@link ScopeChangedEvent} so cross-cutting listeners (audit, MDC propagation)
 * can subscribe on {@code ScopeChangedEvent} and route by scope kind without importing the
 * provisions package. Each concrete variant projects {@code scopeKind = "inventory-item"} and
 * {@code scopeId = affectedItemIds.get(0)} when a single item is in scope.
 */
public sealed interface ProvisionChangedEvent extends ScopeChangedEvent
    permits ItemAddedFromGroceryEvent,
        ItemSpoiledEvent,
        ItemRanOutEvent,
        SubstitutionAcceptedEvent,
        ItemQuantityAdjustedEvent,
        GenericProvisionChangedEvent {

  /** Owning user — the inventory rows belong to this user. Always non-null. */
  UUID userId();

  /**
   * Inventory item IDs touched by this event. The batcher (LLD line 572) collapses multiple
   * deductions in one tx into a single event carrying all affected IDs. May be empty for events
   * (e.g. {@link SubstitutionAcceptedEvent}) that don't directly mutate an inventory row.
   */
  List<UUID> affectedItemIds();

  @Override
  UUID traceId();

  @Override
  Instant occurredAt();
}
