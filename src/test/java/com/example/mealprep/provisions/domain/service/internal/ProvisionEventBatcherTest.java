package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for the package-private {@link ProvisionEventBatcher}. Uses the real {@link
 * TransactionSynchronizationManager} thread-local (initialised/cleared per test) and a real
 * recording {@link ApplicationEventPublisher} — no mocks of the class under test, no Spring
 * context.
 *
 * <p>Covers the two execution modes the production code branches on:
 *
 * <ul>
 *   <li><b>direct-publish</b> (no active tx): each {@code record*} call publishes immediately and
 *       clears, so repeated calls do not double-publish.
 *   <li><b>tx-bound</b> (synchronization active): calls coalesce into one event per key and only
 *       flush on {@code afterCommit}; the {@code afterCompletion} hook unbinds the resource.
 * </ul>
 *
 * Asserts the single-event-per-key contract, within-tx id de-duplication, insertion-ordering of
 * coalesced ids, per-key trace-id capture (first call wins; null → generated), and the {@code
 * wasStaple} flag propagation.
 */
class ProvisionEventBatcherTest {

  private RecordingPublisher publisher;
  private ProvisionEventBatcher batcher;

  private static final class RecordingPublisher implements ApplicationEventPublisher {
    final List<Object> events = new ArrayList<>();

    @Override
    public void publishEvent(Object event) {
      events.add(event);
    }

    @Override
    public void publishEvent(ApplicationEvent event) {
      events.add(event);
    }
  }

  @BeforeEach
  void setUp() {
    publisher = new RecordingPublisher();
    batcher = new ProvisionEventBatcher(publisher);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clear();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private void beginTx() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  private void commitTx() {
    List<TransactionSynchronization> syncs =
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
    syncs.forEach(TransactionSynchronization::afterCommit);
    syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  // ---------------- direct-publish mode (no active tx) ----------------

  @Test
  void recordAdjustment_outsideTx_publishesImmediately() {
    UUID user = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    UUID trace = UUID.randomUUID();

    batcher.recordAdjustment(user, item, ItemAdjustmentSource.MANUAL, trace);

    assertThat(publisher.events).hasSize(1);
    ItemQuantityAdjustedEvent e = (ItemQuantityAdjustedEvent) publisher.events.get(0);
    assertThat(e.userId()).isEqualTo(user);
    assertThat(e.affectedItemIds()).containsExactly(item);
    assertThat(e.source()).isEqualTo(ItemAdjustmentSource.MANUAL);
    assertThat(e.traceId()).isEqualTo(trace);
    assertThat(e.occurredAt()).isNotNull();
  }

  @Test
  void recordAdjustment_outsideTx_secondCall_doesNotDoublePublishFirstItem() {
    UUID user = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    batcher.recordAdjustment(user, first, ItemAdjustmentSource.WASTE, null);
    batcher.recordAdjustment(user, second, ItemAdjustmentSource.WASTE, null);

    // State cleared after each direct flush → two independent single-id events, no replay.
    assertThat(publisher.events).hasSize(2);
    ItemQuantityAdjustedEvent e1 = (ItemQuantityAdjustedEvent) publisher.events.get(0);
    ItemQuantityAdjustedEvent e2 = (ItemQuantityAdjustedEvent) publisher.events.get(1);
    assertThat(e1.affectedItemIds()).containsExactly(first);
    assertThat(e2.affectedItemIds()).containsExactly(second);
  }

  @Test
  void recordAdjustment_outsideTx_nullTrace_generatesTraceId() {
    batcher.recordAdjustment(
        UUID.randomUUID(), UUID.randomUUID(), ItemAdjustmentSource.COOK_EVENT, null);
    ItemQuantityAdjustedEvent e = (ItemQuantityAdjustedEvent) publisher.events.get(0);
    assertThat(e.traceId()).isNotNull();
  }

  @Test
  void recordRanOut_outsideTx_publishesImmediately_withStapleFlag() {
    UUID user = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    batcher.recordRanOut(user, item, "milk", true, null);

    assertThat(publisher.events).hasSize(1);
    ItemRanOutEvent e = (ItemRanOutEvent) publisher.events.get(0);
    assertThat(e.userId()).isEqualTo(user);
    assertThat(e.affectedItemIds()).containsExactly(item);
    assertThat(e.ingredientMappingKey()).isEqualTo("milk");
    assertThat(e.wasStaple()).isTrue();
  }

  @Test
  void recordItemAddedFromGrocery_outsideTx_publishesImmediately() {
    UUID user = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    batcher.recordItemAddedFromGrocery(user, item, "Tesco", "ref-9", null);

    assertThat(publisher.events).hasSize(1);
    ItemAddedFromGroceryEvent e = (ItemAddedFromGroceryEvent) publisher.events.get(0);
    assertThat(e.userId()).isEqualTo(user);
    assertThat(e.affectedItemIds()).containsExactly(item);
    assertThat(e.supplier()).isEqualTo("Tesco");
    assertThat(e.orderRef()).isEqualTo("ref-9");
  }

  // ---------------- transaction-bound mode (synchronization active) ----------------

  @Test
  void nothingPublished_beforeCommit() {
    beginTx();
    batcher.recordAdjustment(
        UUID.randomUUID(), UUID.randomUUID(), ItemAdjustmentSource.MANUAL, null);
    assertThat(publisher.events).isEmpty();
    commitTx();
    assertThat(publisher.events).hasSize(1);
  }

  @Test
  void multipleAdjustments_sameKey_coalesceIntoOneEvent_inInsertionOrder() {
    beginTx();
    UUID user = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID trace = UUID.randomUUID();

    batcher.recordAdjustment(user, a, ItemAdjustmentSource.COOK_EVENT, trace);
    batcher.recordAdjustment(user, b, ItemAdjustmentSource.COOK_EVENT, null);
    batcher.recordAdjustment(user, c, ItemAdjustmentSource.COOK_EVENT, null);
    commitTx();

    assertThat(publisher.events).hasSize(1);
    ItemQuantityAdjustedEvent e = (ItemQuantityAdjustedEvent) publisher.events.get(0);
    assertThat(e.affectedItemIds()).containsExactly(a, b, c);
    // Trace id is captured from the FIRST call for the key.
    assertThat(e.traceId()).isEqualTo(trace);
  }

  @Test
  void duplicateItemId_sameKey_isDeDuplicatedWithinTx() {
    beginTx();
    UUID user = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    batcher.recordAdjustment(user, item, ItemAdjustmentSource.WASTE, null);
    batcher.recordAdjustment(user, item, ItemAdjustmentSource.WASTE, null);
    commitTx();

    assertThat(publisher.events).hasSize(1);
    ItemQuantityAdjustedEvent e = (ItemQuantityAdjustedEvent) publisher.events.get(0);
    assertThat(e.affectedItemIds()).containsExactly(item);
  }

  @Test
  void differentSources_sameUser_produceSeparateEvents() {
    beginTx();
    UUID user = UUID.randomUUID();
    batcher.recordAdjustment(user, UUID.randomUUID(), ItemAdjustmentSource.WASTE, null);
    batcher.recordAdjustment(user, UUID.randomUUID(), ItemAdjustmentSource.COOK_EVENT, null);
    commitTx();

    assertThat(publisher.events).hasSize(2);
    assertThat(publisher.events)
        .allSatisfy(o -> assertThat(o).isInstanceOf(ItemQuantityAdjustedEvent.class));
  }

  @Test
  void ranOut_sameIngredientKey_coalesces_andKeepsFirstWasStaple() {
    beginTx();
    UUID user = UUID.randomUUID();
    UUID i1 = UUID.randomUUID();
    UUID i2 = UUID.randomUUID();
    batcher.recordRanOut(user, i1, "eggs", true, null);
    // wasStaple from the FIRST call is the captured one (later flag ignored for same key).
    batcher.recordRanOut(user, i2, "eggs", false, null);
    commitTx();

    assertThat(publisher.events).hasSize(1);
    ItemRanOutEvent e = (ItemRanOutEvent) publisher.events.get(0);
    assertThat(e.affectedItemIds()).containsExactly(i1, i2);
    assertThat(e.ingredientMappingKey()).isEqualTo("eggs");
    assertThat(e.wasStaple()).isTrue();
  }

  @Test
  void ranOut_differentIngredientKeys_produceSeparateEvents() {
    beginTx();
    UUID user = UUID.randomUUID();
    batcher.recordRanOut(user, UUID.randomUUID(), "eggs", false, null);
    batcher.recordRanOut(user, UUID.randomUUID(), "flour", true, null);
    commitTx();

    assertThat(publisher.events).hasSize(2);
  }

  @Test
  void addedFromGrocery_sameOrder_coalescesAllItemsIntoOneEvent() {
    beginTx();
    UUID user = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    batcher.recordItemAddedFromGrocery(user, a, "Tesco", "order-1", null);
    batcher.recordItemAddedFromGrocery(user, b, "Tesco", "order-1", null);
    commitTx();

    assertThat(publisher.events).hasSize(1);
    ItemAddedFromGroceryEvent e = (ItemAddedFromGroceryEvent) publisher.events.get(0);
    assertThat(e.affectedItemIds()).containsExactly(a, b);
    assertThat(e.supplier()).isEqualTo("Tesco");
    assertThat(e.orderRef()).isEqualTo("order-1");
  }

  @Test
  void addedFromGrocery_differentOrderRef_producesSeparateEvents() {
    beginTx();
    UUID user = UUID.randomUUID();
    batcher.recordItemAddedFromGrocery(user, UUID.randomUUID(), "Tesco", "order-1", null);
    batcher.recordItemAddedFromGrocery(user, UUID.randomUUID(), "Tesco", "order-2", null);
    commitTx();

    assertThat(publisher.events).hasSize(2);
  }

  @Test
  void afterCompletion_unbindsResource_soNextTxStartsFresh() {
    beginTx();
    UUID user = UUID.randomUUID();
    batcher.recordAdjustment(user, UUID.randomUUID(), ItemAdjustmentSource.MANUAL, null);
    commitTx();
    assertThat(publisher.events).hasSize(1);

    // Second, independent tx — resource was unbound in afterCompletion, so this is a clean slate
    // and does not replay the first tx's event.
    beginTx();
    batcher.recordAdjustment(user, UUID.randomUUID(), ItemAdjustmentSource.MANUAL, null);
    commitTx();
    assertThat(publisher.events).hasSize(2);
  }

  @Test
  void mixedEventKinds_inOneTx_eachFlushedOnceOnCommit() {
    beginTx();
    UUID user = UUID.randomUUID();
    batcher.recordAdjustment(user, UUID.randomUUID(), ItemAdjustmentSource.COOK_EVENT, null);
    batcher.recordRanOut(user, UUID.randomUUID(), "milk", true, null);
    batcher.recordItemAddedFromGrocery(user, UUID.randomUUID(), "Tesco", "o-1", null);
    assertThat(publisher.events).isEmpty();
    commitTx();

    assertThat(publisher.events).hasSize(3);
    assertThat(publisher.events.stream().map(Object::getClass))
        .containsExactlyInAnyOrder(
            ItemQuantityAdjustedEvent.class,
            ItemRanOutEvent.class,
            ItemAddedFromGroceryEvent.class);
  }
}
