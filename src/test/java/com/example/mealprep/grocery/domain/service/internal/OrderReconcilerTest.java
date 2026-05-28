package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.event.GroceryOrderReconciledEvent;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.OrderHasOutstandingProposalsException;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test for {@link OrderReconciler} (grocery-01f). Covers tryReconcile / reconcile, the
 * outstanding-proposal gate, the per-delivered-line paid-observation write, inventory add via
 * applyGroceryOrder (idempotent DuplicateGroceryImportException swallowing), arrived/excluded line
 * classification, paid/confirmed/quoted unit-pence fallback, paid_total accumulation, and the
 * single-event publish guarantees. Pure unit test — all collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class OrderReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private GroceryOrderDataGateway dataGateway;
  @Mock private PriceObservationWriter priceObservationWriter;
  @Mock private ProvisionUpdateService provisionUpdateService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private OrderReconciler reconciler() {
    return new OrderReconciler(
        dataGateway,
        new OrderStateMachine(),
        priceObservationWriter,
        provisionUpdateService,
        eventPublisher,
        clock);
  }

  private GroceryOrder order(GroceryOrderStatus status, GroceryOrderLine... lines) {
    UUID orderId = UUID.randomUUID();
    GroceryOrder order =
        GroceryOrder.builder()
            .id(orderId)
            .userId(UUID.randomUUID())
            .householdId(UUID.randomUUID())
            .shoppingListId(UUID.randomUUID())
            .providerKey("fake")
            .status(status)
            .currency("GBP")
            .traceId(orderId)
            .automationFailureLog(new ArrayList<>())
            .lines(new ArrayList<>(List.of(lines)))
            .build();
    for (GroceryOrderLine l : lines) {
      l.setGroceryOrder(order);
    }
    return order;
  }

  private GroceryOrderLine line(
      String key, OrderLineStatus status, Integer paidPence, Integer confirmedPence) {
    Instant now = Instant.now();
    return GroceryOrderLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .displayName(capitalise(key))
        .providerProductId("sku-" + key)
        .quantityRequested(new BigDecimal("1.000"))
        .quantityUnit("kg")
        .packSizeG(500)
        .packCountRequested(2)
        .packCountDelivered(2)
        .paidUnitPence(paidPence)
        .confirmedUnitPence(confirmedPence)
        .lineStatus(status)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private GroceryImportResultDto emptyImport() {
    return new GroceryImportResultDto(List.of(), List.of(), List.of(), List.of());
  }

  // ============================== outstanding-proposal gate ==============================

  @Test
  void tryReconcile_outstandingProposals_silentlyNoOps_andReturnsFalse() {
    UUID orderId = UUID.randomUUID();
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(orderId), anyList())).thenReturn(2L);

    boolean reconciled = reconciler().tryReconcile(orderId);

    assertThat(reconciled).isFalse();
    // Critical: when the gate is closed, NOTHING beyond the count is touched — no order load,
    // no inventory call, no event, no observations.
    verify(dataGateway, never()).findOrderWithLinesById(any());
    verifyNoInteractions(priceObservationWriter, provisionUpdateService, eventPublisher);
  }

  @Test
  void tryReconcile_zeroOutstandingButOrderMissing_throws404() {
    UUID orderId = UUID.randomUUID();
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(orderId), anyList())).thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(orderId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reconciler().tryReconcile(orderId))
        .isInstanceOf(GroceryOrderNotFoundException.class);
  }

  @Test
  void reconcile_explicitWithOutstandingProposals_throws422_carriesOrderIdAndCount() {
    GroceryOrder order =
        order(GroceryOrderStatus.DELIVERED, line("flour", OrderLineStatus.DELIVERED, 100, null));
    // The gate query is consulted TWICE — once before the order load, once after — both must say
    // there are outstanding proposals for the explicit path to throw.
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(3L);
    lenient()
        .when(dataGateway.findOrderWithLinesById(order.getId()))
        .thenReturn(Optional.of(order));

    assertThatThrownBy(() -> reconciler().reconcile(order.getId()))
        .isInstanceOf(OrderHasOutstandingProposalsException.class)
        .satisfies(
            ex -> {
              OrderHasOutstandingProposalsException e = (OrderHasOutstandingProposalsException) ex;
              assertThat(e.orderId()).isEqualTo(order.getId());
              assertThat(e.outstandingCount()).isEqualTo(3L);
            });

    // No inventory / event / status mutation on the throw path.
    verify(provisionUpdateService, never()).applyGroceryOrder(any(), any());
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderReconciledEvent.class));
  }

  @Test
  void tryReconcile_zeroOutstanding_butAlreadyReconciled_returnsFalse_noReWrite_noSecondEvent() {
    GroceryOrder order =
        order(GroceryOrderStatus.RECONCILED, line("flour", OrderLineStatus.DELIVERED, 100, null));
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    boolean reconciled = reconciler().tryReconcile(order.getId());

    assertThat(reconciled).isFalse();
    verify(dataGateway, never()).saveOrder(any());
    verify(provisionUpdateService, never()).applyGroceryOrder(any(), any());
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderReconciledEvent.class));
    verifyNoInteractions(priceObservationWriter);
  }

  @Test
  void tryReconcile_outstandingProposalsDetectedSecondTime_isSilentNoOp_notThrow() {
    // The second count check (after load) finds outstanding proposals — assertGate=false, so just
    // return false silently (no throw — that path is the explicit reconcile() only).
    GroceryOrder order =
        order(GroceryOrderStatus.DELIVERED, line("flour", OrderLineStatus.DELIVERED, 100, null));
    // First check: zero. Second check (inside reconcileInternal): non-zero.
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L, 1L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    boolean reconciled = reconciler().tryReconcile(order.getId());

    assertThat(reconciled).isFalse();
    verify(dataGateway, never()).saveOrder(any());
    verify(eventPublisher, never()).publishEvent(any(GroceryOrderReconciledEvent.class));
  }

  // ============================== happy-path reconcile ==============================

  @Test
  void reconcile_happyPath_writesPaidObservationPerDeliveredLine_addsInventory_publishesOneEvent() {
    GroceryOrderLine a = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrderLine b = line("rice", OrderLineStatus.DELIVERED, 200, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, a, b);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    boolean reconciled = reconciler().tryReconcile(order.getId());

    assertThat(reconciled).isTrue();
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.RECONCILED);
    assertThat(order.getReconciledAt()).isEqualTo(NOW);
    assertThat(order.getStatusReason()).isNull();

    // Two paid observations — one per delivered line. Each emits its own PriceObservedEvent
    // via the writer (the writer publishes its own event; we don't double-publish here).
    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter, times(2)).write(cmd.capture());
    assertThat(cmd.getAllValues()).allMatch(w -> w.source() == PriceSource.PAID);

    // Inventory called once with all delivered lines.
    ArgumentCaptor<GroceryOrderImportCommand> imp =
        ArgumentCaptor.forClass(GroceryOrderImportCommand.class);
    verify(provisionUpdateService).applyGroceryOrder(eq(order.getUserId()), imp.capture());
    assertThat(imp.getValue().lines()).hasSize(2);
    assertThat(imp.getValue().orderRef()).isEqualTo(order.getId().toString());
    assertThat(imp.getValue().supplier()).isEqualTo("fake");

    // Exactly one reconciled event.
    ArgumentCaptor<GroceryOrderReconciledEvent> evt =
        ArgumentCaptor.forClass(GroceryOrderReconciledEvent.class);
    verify(eventPublisher, times(1)).publishEvent(evt.capture());
    assertThat(evt.getValue().groceryOrderId()).isEqualTo(order.getId());
    assertThat(evt.getValue().userId()).isEqualTo(order.getUserId());
    // paidTotal = 2×100 + 2×200 = 600
    assertThat(evt.getValue().paidTotalPence()).isEqualTo(600);
    assertThat(order.getPaidTotalPence()).isEqualTo(600);
  }

  @Test
  void reconcile_setsReconciledAt_andStatusReasonCleared() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setStatusReason("some stale reason");
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.RECONCILED);
    assertThat(order.getReconciledAt()).isEqualTo(NOW);
    assertThat(order.getStatusReason()).isNull(); // cleared on reconcile
    verify(dataGateway).saveOrder(order);
  }

  @Test
  void reconcile_preservesPaidTotalPence_whenAlreadySetByCaller() {
    // The applyStatusTotals path on refreshStatus may have already stamped paid_total from the
    // provider before reconcile runs; the reconciler must NOT clobber a non-null paid_total.
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setPaidTotalPence(999); // pre-set
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    assertThat(order.getPaidTotalPence())
        .isEqualTo(999); // unchanged — the line sum did NOT replace it.
  }

  // ============================== delivered-line classification ==============================

  @Test
  void reconcile_includesArrivedLines_excludesUnavailableAndRejected() {
    // DELIVERED + ADDED + ADDED_PARTIAL + SUBSTITUTED count; QUEUED + UNAVAILABLE + REJECTED do
    // not.
    GroceryOrderLine delivered = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrderLine added = line("rice", OrderLineStatus.ADDED, 150, null);
    GroceryOrderLine addedPartial = line("eggs", OrderLineStatus.ADDED_PARTIAL, 250, null);
    GroceryOrderLine substituted = line("sugar", OrderLineStatus.SUBSTITUTED, 80, null);
    GroceryOrderLine unavailable = line("salt", OrderLineStatus.UNAVAILABLE, 50, null);
    GroceryOrderLine rejected = line("pepper", OrderLineStatus.REJECTED, 60, null);
    GroceryOrderLine queued = line("oats", OrderLineStatus.QUEUED, 70, null);

    GroceryOrder order =
        order(
            GroceryOrderStatus.DELIVERED,
            delivered,
            added,
            addedPartial,
            substituted,
            unavailable,
            rejected,
            queued);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    // 4 arrived lines → 4 observations + 4 lines on the inventory import.
    verify(priceObservationWriter, times(4)).write(any());
    ArgumentCaptor<GroceryOrderImportCommand> imp =
        ArgumentCaptor.forClass(GroceryOrderImportCommand.class);
    verify(provisionUpdateService).applyGroceryOrder(any(), imp.capture());
    assertThat(imp.getValue().lines()).hasSize(4);
  }

  @Test
  void reconcile_unavailableLine_isExcluded_evenWhenPaidPriceIsKnown() {
    // GROC-21: an UNAVAILABLE line did NOT arrive; even if some price was attached, it must not
    // produce inventory or an observation.
    GroceryOrderLine unavailable = line("flour", OrderLineStatus.UNAVAILABLE, 100, 100);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, unavailable);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    reconciler().tryReconcile(order.getId());

    verify(priceObservationWriter, never()).write(any());
    // The inventory call is skipped entirely when no lines arrived (the early-return branch).
    verify(provisionUpdateService, never()).applyGroceryOrder(any(), any());
  }

  @Test
  void reconcile_emptyDeliveredLines_skipsInventoryCall_butStillReconciles() {
    // No delivered lines (all UNAVAILABLE/REJECTED) → no inventory call (the early-return guard).
    // The order should still transition to RECONCILED and publish the event.
    GroceryOrderLine unavailable = line("flour", OrderLineStatus.UNAVAILABLE, null, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, unavailable);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));

    boolean reconciled = reconciler().tryReconcile(order.getId());

    assertThat(reconciled).isTrue();
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.RECONCILED);
    verify(provisionUpdateService, never()).applyGroceryOrder(any(), any());
    verify(eventPublisher).publishEvent(any(GroceryOrderReconciledEvent.class));
  }

  // ============================== paid unit pence fallback ==============================

  @Test
  void paidObservation_usesPaidUnitPence_first() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, 200);
    ln.setQuotedUnitPence(300);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(100); // paid wins
  }

  @Test
  void paidObservation_fallsBackToConfirmed_whenPaidIsNull() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, null, 200);
    ln.setQuotedUnitPence(300);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(200); // confirmed used
  }

  @Test
  void paidObservation_fallsBackToQuoted_whenPaidAndConfirmedAreNull() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, null, null);
    ln.setQuotedUnitPence(300);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().paidTotalPence()).isEqualTo(300); // quoted used
  }

  @Test
  void paidObservation_skippedWhenNoPriceKnown_inventoryStillAdded() {
    // No paid/confirmed/quoted price → no observation row, but the inventory add proceeds.
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, null, null);
    ln.setQuotedUnitPence(null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    verify(priceObservationWriter, never()).write(any());
    verify(provisionUpdateService).applyGroceryOrder(any(), any());
    assertThat(order.getPaidTotalPence()).isEqualTo(0); // line contributed nothing
  }

  // ============================== pack count + paid total accumulation
  // ==============================

  @Test
  void paidTotal_isUnitPenceTimesPackCountDelivered_perLine_summed() {
    GroceryOrderLine a = line("flour", OrderLineStatus.DELIVERED, 100, null);
    a.setPackCountDelivered(3);
    GroceryOrderLine b = line("rice", OrderLineStatus.DELIVERED, 50, null);
    b.setPackCountDelivered(4);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, a, b);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    // 100×3 + 50×4 = 500
    assertThat(order.getPaidTotalPence()).isEqualTo(500);
  }

  @Test
  void paidTotal_packCountDeliveredNull_fallsBackToRequested() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    ln.setPackCountDelivered(null);
    ln.setPackCountRequested(5);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    assertThat(order.getPaidTotalPence()).isEqualTo(500); // 100×5
  }

  @Test
  void paidTotal_bothPackCountsNull_fallsBackToOne() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    ln.setPackCountDelivered(null);
    ln.setPackCountRequested(null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    assertThat(order.getPaidTotalPence()).isEqualTo(100); // 100×1
  }

  // ============================== idempotent inventory add ==============================

  @Test
  void inventoryAdd_orderRefIsOrderIdString_supplierIsProviderKey() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setProviderKey("tesco");
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<GroceryOrderImportCommand> imp =
        ArgumentCaptor.forClass(GroceryOrderImportCommand.class);
    verify(provisionUpdateService).applyGroceryOrder(eq(order.getUserId()), imp.capture());
    assertThat(imp.getValue().orderRef()).isEqualTo(order.getId().toString());
    assertThat(imp.getValue().supplier()).isEqualTo("tesco");
  }

  @Test
  void inventoryAdd_blankProviderKey_fallsBackToGrocerySupplier() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setProviderKey(""); // blank
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<GroceryOrderImportCommand> imp =
        ArgumentCaptor.forClass(GroceryOrderImportCommand.class);
    verify(provisionUpdateService).applyGroceryOrder(any(), imp.capture());
    assertThat(imp.getValue().supplier()).isEqualTo("grocery");
  }

  @Test
  void inventoryAdd_nullProviderKey_fallsBackToGrocerySupplier() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setProviderKey(null);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<GroceryOrderImportCommand> imp =
        ArgumentCaptor.forClass(GroceryOrderImportCommand.class);
    verify(provisionUpdateService).applyGroceryOrder(any(), imp.capture());
    assertThat(imp.getValue().supplier()).isEqualTo("grocery");
  }

  @Test
  void inventoryAdd_duplicateGroceryImport_isSwallowed_reconcileStillCompletes() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any()))
        .thenThrow(
            new DuplicateGroceryImportException(
                order.getUserId(), ItemSource.TESCO_ORDER, order.getId().toString()));

    boolean reconciled = reconciler().tryReconcile(order.getId());

    assertThat(reconciled).isTrue(); // duplicate is swallowed (idempotent re-reconcile)
    assertThat(order.getStatus()).isEqualTo(GroceryOrderStatus.RECONCILED);
    verify(eventPublisher).publishEvent(any(GroceryOrderReconciledEvent.class));
  }

  // ============================== householdId fallback ==============================

  @Test
  void paidObservation_writeCommand_useshouseholdId_fallsBackToUserId() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    order.setHouseholdId(null); // null → falls back to userId for the observation scope
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().householdId()).isEqualTo(order.getUserId());
  }

  @Test
  void paidObservation_writeCommand_useshouseholdId_whenPresent() {
    GroceryOrderLine ln = line("flour", OrderLineStatus.DELIVERED, 100, null);
    GroceryOrder order = order(GroceryOrderStatus.DELIVERED, ln);
    UUID household = order.getHouseholdId(); // already set in `order()`
    assertThat(household).isNotNull();
    when(dataGateway.countProposalsByOrderIdAndStatusIn(eq(order.getId()), anyList()))
        .thenReturn(0L);
    when(dataGateway.findOrderWithLinesById(order.getId())).thenReturn(Optional.of(order));
    when(provisionUpdateService.applyGroceryOrder(any(), any())).thenReturn(emptyImport());

    reconciler().tryReconcile(order.getId());

    ArgumentCaptor<PriceObservationWriter.WriteCommand> cmd =
        ArgumentCaptor.forClass(PriceObservationWriter.WriteCommand.class);
    verify(priceObservationWriter).write(cmd.capture());
    assertThat(cmd.getValue().householdId()).isEqualTo(household);
  }
}
