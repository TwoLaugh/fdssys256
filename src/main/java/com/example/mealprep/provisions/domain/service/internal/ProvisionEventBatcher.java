package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Per-transaction batcher that coalesces multiple inventory adjustments into a single {@link
 * ItemQuantityAdjustedEvent} (keyed by {@code (userId, source)}) and a single {@link
 * ItemRanOutEvent} per {@code (userId, ingredientMappingKey)} pair, published at {@code
 * AFTER_COMMIT}. Implements the "Single-event-per-operation contract" from LLD line 572.
 *
 * <p>Per-tx state is registered via {@link TransactionSynchronizationManager}; when invoked outside
 * an active tx the batcher degrades to direct publication.
 */
@Component
class ProvisionEventBatcher {

  private static final String SYNC_KEY = "ProvisionEventBatcher.state";

  private final ApplicationEventPublisher publisher;

  ProvisionEventBatcher(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Record one adjusted item id for {@code (userId, source)}. Items are de-duplicated within a tx —
   * invoking twice for the same id is a no-op. Trace id is taken from the first call for a given
   * key.
   */
  void recordAdjustment(UUID userId, UUID itemId, ItemAdjustmentSource source, UUID traceId) {
    State s = state();
    AdjustKey key = new AdjustKey(userId, source);
    AdjustValue v =
        s.adjust.computeIfAbsent(
            key,
            k ->
                new AdjustValue(
                    new LinkedHashMap<>(), traceId == null ? UUID.randomUUID() : traceId));
    v.itemIds.putIfAbsent(itemId, Boolean.TRUE);
    if (s.directPublish) {
      flushDirect(s);
    }
  }

  /**
   * Record one added-from-grocery item id for {@code (userId, supplier, orderRef)}. Items are
   * de-duplicated within a tx; a duplicate {@code itemId} for the same key is a no-op. Trace id is
   * taken from the first call for a given key. Implements the single-event-per-operation contract
   * (LLD line 572) for {@link ItemAddedFromGroceryEvent} — one import touching 15 items emits
   * exactly one event carrying all 15 ids.
   */
  void recordItemAddedFromGrocery(
      UUID userId, UUID itemId, String supplier, String orderRef, UUID traceId) {
    State s = state();
    AddedFromGroceryKey key = new AddedFromGroceryKey(userId, supplier, orderRef);
    AddedFromGroceryValue v =
        s.addedFromGrocery.computeIfAbsent(
            key,
            k ->
                new AddedFromGroceryValue(
                    new LinkedHashMap<>(), traceId == null ? UUID.randomUUID() : traceId));
    v.itemIds.putIfAbsent(itemId, Boolean.TRUE);
    if (s.directPublish) {
      flushDirect(s);
    }
  }

  /**
   * Record one ran-out item id for {@code (userId, ingredientMappingKey)}. {@code wasStaple}
   * mirrors the row's staple flag at the time of the transition.
   */
  void recordRanOut(
      UUID userId, UUID itemId, String ingredientMappingKey, boolean wasStaple, UUID traceId) {
    State s = state();
    RanOutKey key = new RanOutKey(userId, ingredientMappingKey);
    RanOutValue v =
        s.ranOut.computeIfAbsent(
            key,
            k ->
                new RanOutValue(
                    new LinkedHashMap<>(),
                    wasStaple,
                    traceId == null ? UUID.randomUUID() : traceId));
    v.itemIds.putIfAbsent(itemId, Boolean.TRUE);
    if (s.directPublish) {
      flushDirect(s);
    }
  }

  private State state() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      State s = new State();
      s.directPublish = true;
      return s;
    }
    State s = (State) TransactionSynchronizationManager.getResource(SYNC_KEY);
    if (s == null) {
      s = new State();
      TransactionSynchronizationManager.bindResource(SYNC_KEY, s);
      TransactionSynchronizationManager.registerSynchronization(new Sync(s));
    }
    return s;
  }

  private void flushDirect(State s) {
    // No tx in flight — publish immediately and clear so we don't double-publish on next call.
    flushAfterCommit(s);
    s.adjust.clear();
    s.ranOut.clear();
    s.addedFromGrocery.clear();
  }

  private void flushAfterCommit(State s) {
    Instant now = Instant.now();
    s.adjust.forEach(
        (k, v) ->
            publisher.publishEvent(
                new ItemQuantityAdjustedEvent(
                    k.userId, List.copyOf(v.itemIds.keySet()), k.source, v.traceId, now)));
    s.ranOut.forEach(
        (k, v) ->
            publisher.publishEvent(
                new ItemRanOutEvent(
                    k.userId,
                    List.copyOf(v.itemIds.keySet()),
                    k.ingredientMappingKey,
                    v.wasStaple,
                    v.traceId,
                    now)));
    s.addedFromGrocery.forEach(
        (k, v) ->
            publisher.publishEvent(
                new ItemAddedFromGroceryEvent(
                    k.userId,
                    List.copyOf(v.itemIds.keySet()),
                    k.supplier,
                    k.orderRef,
                    v.traceId,
                    now)));
  }

  /**
   * Per-tx state — re-keyed Maps preserve insertion order, which determines event-list ordering.
   */
  private static final class State {
    final Map<AdjustKey, AdjustValue> adjust = new LinkedHashMap<>();
    final Map<RanOutKey, RanOutValue> ranOut = new LinkedHashMap<>();
    final Map<AddedFromGroceryKey, AddedFromGroceryValue> addedFromGrocery = new LinkedHashMap<>();
    boolean directPublish;
  }

  private record AdjustKey(UUID userId, ItemAdjustmentSource source) {}

  private static final class AdjustValue {
    final Map<UUID, Boolean> itemIds;
    final UUID traceId;

    AdjustValue(Map<UUID, Boolean> itemIds, UUID traceId) {
      this.itemIds = itemIds;
      this.traceId = traceId;
    }
  }

  private record RanOutKey(UUID userId, String ingredientMappingKey) {
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof RanOutKey that)) return false;
      return Objects.equals(userId, that.userId)
          && Objects.equals(ingredientMappingKey, that.ingredientMappingKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, ingredientMappingKey);
    }
  }

  private static final class RanOutValue {
    final Map<UUID, Boolean> itemIds;
    final boolean wasStaple;
    final UUID traceId;

    RanOutValue(Map<UUID, Boolean> itemIds, boolean wasStaple, UUID traceId) {
      this.itemIds = itemIds;
      this.wasStaple = wasStaple;
      this.traceId = traceId;
    }
  }

  private record AddedFromGroceryKey(UUID userId, String supplier, String orderRef) {
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AddedFromGroceryKey that)) return false;
      return Objects.equals(userId, that.userId)
          && Objects.equals(supplier, that.supplier)
          && Objects.equals(orderRef, that.orderRef);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, supplier, orderRef);
    }
  }

  private static final class AddedFromGroceryValue {
    final Map<UUID, Boolean> itemIds;
    final UUID traceId;

    AddedFromGroceryValue(Map<UUID, Boolean> itemIds, UUID traceId) {
      this.itemIds = itemIds;
      this.traceId = traceId;
    }
  }

  private final class Sync implements TransactionSynchronization {
    private final State state;

    Sync(State state) {
      this.state = state;
    }

    @Override
    public void afterCommit() {
      flushAfterCommit(state);
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(SYNC_KEY);
    }
  }
}
