package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.BoughtVia;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.event.GroceryOrderReconciledEvent;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.OrderHasOutstandingProposalsException;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tier-3 reconciliation (grocery-01f, LLD line 913 / Flow 4 step 7). The single inventory-add
 * trigger and the paid-price-observation writer for a delivered order. Package-private internal
 * plumbing; invoked from {@code GroceryOrderServiceImpl} after a delivery and after each
 * substitution resolve.
 *
 * <p><b>The reconcile gate.</b> An order may only reconcile when NO proposal remains {@code
 * PENDING_USER_REVIEW} or {@code UNPARSED}. {@link #tryReconcile} is the AUTO path (called from the
 * resolve commit and from {@code markDelivered}) — it SILENTLY no-ops while proposals remain, so a
 * resolve that leaves siblings outstanding does not fail. {@link #reconcile} is the explicit path
 * that THROWS {@link OrderHasOutstandingProposalsException} (422, GROC-21) when forced while
 * proposals remain.
 *
 * <p><b>Inventory at reconcile, not confirm (the load-bearing seam).</b> Inventory is added here
 * via the canonical {@link ProvisionUpdateService#applyGroceryOrder} (the same path Tier 2 uses),
 * reflecting the actually-delivered + substitution-resolved lines: a delivered line is included; a
 * line whose substitution was REJECTED excludes the original. {@code orderRef} is the grocery order
 * id, so a re-reconcile (retry) is idempotent — provisions rejects the replay with {@link
 * DuplicateGroceryImportException} and we treat it as already-applied (belt-and-braces alongside
 * the {@code status == RECONCILED} early-return guard).
 *
 * <p><b>Paid-price observations.</b> One {@code PAID} observation (weight 1.0) per delivered line
 * via 01c's {@link PriceObservationWriter}; each emits its own {@code PriceObservedEvent}. The
 * {@code GroceryOrderReconciledEvent} fires exactly ONCE, AFTER all paid rows are written (LLD line
 * 837).
 *
 * <p><b>Source shopping-list-line write-back (grocery-1).</b> Reconciliation closes the Tier-1
 * loop: each order line carries a soft FK ({@code shoppingListLineId}) back to the {@link
 * ShoppingListLine} it was cloned from at {@code createDraft}. At reconcile we load those source
 * lines and stamp their fulfilment so the rendered list reflects what actually arrived — {@code
 * fulfilment_status} = {@code BOUGHT} (delivered/added), {@code SUBSTITUTED} (an accepted
 * substitution), or {@code DROPPED} (a rejected substitution or an unavailable line), {@code
 * bought_via = ORDER}, the {@code bought_*} fields (quantity / unit / price / at), and {@code
 * grocery_order_id}. Each touched line's parent {@link ShoppingList} {@code @Version} is bumped
 * (mirroring the Tier-2 mark-bought seam) so a concurrent list edit collides. A QUEUED line (never
 * placed) is left untouched.
 *
 * <p><b>Transaction.</b> Both entry points run inside the CALLER'S transaction (the public
 * {@code @Transactional} {@code resolveSubstitution} / {@code markDelivered} / {@code
 * refreshStatus} methods). The methods are package-private, so a {@code @Transactional} here would
 * be a proxy no-op; the published events therefore fire {@code AFTER_COMMIT} of the caller's
 * transaction.
 */
@Component
class OrderReconciler {

  private static final Logger log = LoggerFactory.getLogger(OrderReconciler.class);

  private static final String CURRENCY_GBP = "GBP";

  /** The statuses that block reconciliation while present. */
  private static final List<SubstitutionProposalStatus> BLOCKING_STATUSES =
      List.of(SubstitutionProposalStatus.PENDING_USER_REVIEW, SubstitutionProposalStatus.UNPARSED);

  private final GroceryOrderDataGateway dataGateway;
  private final ShoppingListDataGateway shoppingListDataGateway;
  private final OrderStateMachine stateMachine;
  private final PriceObservationWriter priceObservationWriter;
  private final ProvisionUpdateService provisionUpdateService;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  OrderReconciler(
      GroceryOrderDataGateway dataGateway,
      ShoppingListDataGateway shoppingListDataGateway,
      OrderStateMachine stateMachine,
      PriceObservationWriter priceObservationWriter,
      ProvisionUpdateService provisionUpdateService,
      org.springframework.context.ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.dataGateway = dataGateway;
    this.shoppingListDataGateway = shoppingListDataGateway;
    this.stateMachine = stateMachine;
    this.priceObservationWriter = priceObservationWriter;
    this.provisionUpdateService = provisionUpdateService;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * AUTO reconcile path: runs only when the gate is clear (no outstanding proposal). A no-op while
   * proposals remain (so a resolve that leaves siblings pending does not fail), and a no-op when
   * the order has already reconciled (idempotent re-entry). Returns {@code true} iff it actually
   * reconciled on this call.
   */
  boolean tryReconcile(UUID orderId) {
    if (countOutstanding(orderId) > 0) {
      return false; // gate not clear — silent no-op (proposals still pending/unparsed)
    }
    return reconcileInternal(orderId, false);
  }

  /**
   * EXPLICIT reconcile path (GROC-21): asserts the gate and THROWS {@link
   * OrderHasOutstandingProposalsException} (422) when forced while proposals remain. Used to
   * enforce the "blocked while pending" contract.
   */
  boolean reconcile(UUID orderId) {
    return reconcileInternal(orderId, true);
  }

  private boolean reconcileInternal(UUID orderId, boolean assertGate) {
    GroceryOrder order =
        dataGateway
            .findOrderWithLinesById(orderId)
            .orElseThrow(() -> new GroceryOrderNotFoundException(orderId));

    // Idempotent re-entry: an already-reconciled order is a no-op (no re-write, no second event).
    if (order.getStatus() == GroceryOrderStatus.RECONCILED) {
      return false;
    }

    long outstanding = countOutstanding(orderId);
    if (outstanding > 0) {
      if (assertGate) {
        throw new OrderHasOutstandingProposalsException(orderId, outstanding);
      }
      return false;
    }

    stateMachine.assertCanTransition(order.getStatus(), GroceryOrderStatus.RECONCILED);

    Instant now = clock.instant();
    List<GroceryOrderLine> deliveredLines = deliveredLines(order);

    // (2) Paid-price observations — PAID, weight 1.0 — one per delivered line (each fires its own
    // PriceObservedEvent via the writer). Done BEFORE the reconciled event (LLD line 837).
    int paidTotal = 0;
    for (GroceryOrderLine line : deliveredLines) {
      Integer linePaid = writePaidObservation(order, line, now);
      if (linePaid != null) {
        paidTotal += linePaid;
      }
    }

    // (3) Inventory add via the canonical, idempotent applyGroceryOrder. orderRef = order id.
    addInventory(order, deliveredLines, now);

    // (3b) Source shopping-list-line write-back (grocery-1): stamp fulfilment on the Tier-1 lines
    // the order was cloned from, so the rendered list reflects what arrived.
    writeSourceLineFulfilment(order, now);

    // (4) paid_total_pence + RECONCILED + reconciled_at, then publish the reconciled event ONCE.
    if (order.getPaidTotalPence() == null) {
      order.setPaidTotalPence(paidTotal);
    }
    order.setStatus(GroceryOrderStatus.RECONCILED);
    order.setReconciledAt(now);
    order.setStatusReason(null);
    dataGateway.saveOrder(order);

    eventPublisher.publishEvent(
        new GroceryOrderReconciledEvent(
            order.getUserId(), order.getId(), order.getPaidTotalPence(), order.getTraceId(), now));
    return true;
  }

  private long countOutstanding(UUID orderId) {
    return dataGateway.countProposalsByOrderIdAndStatusIn(orderId, BLOCKING_STATUSES);
  }

  /**
   * The lines that actually arrived: DELIVERED / ADDED / ADDED_PARTIAL / SUBSTITUTED. A line marked
   * UNAVAILABLE or REJECTED did not arrive and is excluded (a rejected substitution leaves its line
   * out — the original is not added).
   */
  private static List<GroceryOrderLine> deliveredLines(GroceryOrder order) {
    List<GroceryOrderLine> out = new ArrayList<>();
    if (order.getLines() == null) {
      return out;
    }
    for (GroceryOrderLine line : order.getLines()) {
      if (arrived(line.getLineStatus())) {
        out.add(line);
      }
    }
    return out;
  }

  private static boolean arrived(OrderLineStatus status) {
    return status == OrderLineStatus.DELIVERED
        || status == OrderLineStatus.ADDED
        || status == OrderLineStatus.ADDED_PARTIAL
        || status == OrderLineStatus.SUBSTITUTED;
  }

  /**
   * Write one PAID observation (weight 1.0) for a delivered line. The paid unit pence is the line's
   * paid (falling back to confirmed, then quoted) unit pence; null when no price is known (no
   * observation written, returns null). Returns the per-line paid total (unit × packs) for the
   * order's {@code paid_total_pence} accumulation.
   */
  private Integer writePaidObservation(GroceryOrder order, GroceryOrderLine line, Instant now) {
    Integer unitPence = paidUnitPence(line);
    if (unitPence == null) {
      return null;
    }
    int packCount =
        line.getPackCountDelivered() != null
            ? line.getPackCountDelivered()
            : line.getPackCountRequested() != null ? line.getPackCountRequested() : 1;
    UUID householdId = order.getHouseholdId() != null ? order.getHouseholdId() : order.getUserId();
    priceObservationWriter.write(
        new PriceObservationWriter.WriteCommand(
            order.getUserId(),
            householdId,
            line.getIngredientMappingKey(),
            order.getProviderKey(),
            line.getProviderProductId(),
            line.getPackSizeG(),
            packCount,
            line.getQuantityRequested(),
            line.getQuantityUnit(),
            unitPence,
            order.getCurrency() != null ? order.getCurrency() : CURRENCY_GBP,
            PriceSource.PAID,
            order.getId(),
            line.getShoppingListLineId(),
            now,
            line.getNote()));
    return unitPence * Math.max(packCount, 1);
  }

  private static Integer paidUnitPence(GroceryOrderLine line) {
    if (line.getPaidUnitPence() != null) {
      return line.getPaidUnitPence();
    }
    if (line.getConfirmedUnitPence() != null) {
      return line.getConfirmedUnitPence();
    }
    return line.getQuotedUnitPence();
  }

  /**
   * Add the delivered lines to provisions inventory via the canonical {@code applyGroceryOrder}.
   * {@code orderRef = order id} → idempotency. A retry hits {@link DuplicateGroceryImportException}
   * (provisions' {@code (userId, supplier, orderRef)} log key) — treated as already-applied (no
   * double-add), so re-reconcile is safe.
   */
  private void addInventory(
      GroceryOrder order, List<GroceryOrderLine> deliveredLines, Instant now) {
    if (deliveredLines.isEmpty()) {
      return;
    }
    List<com.example.mealprep.provisions.api.dto.GroceryOrderLine> lines =
        new ArrayList<>(deliveredLines.size());
    for (GroceryOrderLine line : deliveredLines) {
      lines.add(toProvisionLine(line));
    }
    GroceryOrderImportCommand command =
        new GroceryOrderImportCommand(
            supplier(order.getProviderKey()),
            order.getId().toString(), // orderRef == grocery order id → idempotency key
            LocalDate.ofInstant(now, java.time.ZoneOffset.UTC),
            lines,
            List.of(),
            order.getTraceId());
    try {
      provisionUpdateService.applyGroceryOrder(order.getUserId(), command);
    } catch (DuplicateGroceryImportException alreadyApplied) {
      // Re-reconcile (retry): inventory was already added under this order-id orderRef. Idempotent
      // no-op — do NOT propagate the 409; the reconcile still completes.
      log.info(
          "Re-reconcile of order {} hit DuplicateGroceryImportException; inventory already applied"
              + " (idempotent no-op).",
          order.getId());
    }
  }

  /**
   * Stamp fulfilment on the source {@link ShoppingListLine}s the order lines were cloned from
   * (grocery-1). Loads every distinct {@code shoppingListLineId} referenced by the order lines in
   * one batch (parents eagerly joined), maps each order line's {@link OrderLineStatus} to a {@link
   * LineFulfilmentStatus}, sets the {@code bought_*} / {@code bought_via = ORDER} / {@code
   * grocery_order_id} fields for the arrived ones, and bumps each touched parent list's
   * {@code @Version}. A line already {@code BOUGHT} (e.g. via a prior Tier-2 mark-bought) is left
   * as-is so we never clobber a manual fulfilment with order data. Order lines with no source FK (a
   * provider-added line that maps to no list line) are skipped.
   */
  private void writeSourceLineFulfilment(GroceryOrder order, Instant now) {
    if (order.getLines() == null || order.getLines().isEmpty()) {
      return;
    }
    // Collect the order line per source-line id (a source line maps to at most one order line).
    Map<UUID, GroceryOrderLine> orderLineBySource = new HashMap<>();
    for (GroceryOrderLine line : order.getLines()) {
      UUID sourceId = line.getShoppingListLineId();
      if (sourceId != null) {
        orderLineBySource.putIfAbsent(sourceId, line);
      }
    }
    if (orderLineBySource.isEmpty()) {
      return;
    }

    List<ShoppingListLine> sourceLines =
        shoppingListDataGateway.findLinesByIds(orderLineBySource.keySet());
    Map<UUID, ShoppingList> touchedParents = new HashMap<>();
    for (ShoppingListLine sourceLine : sourceLines) {
      GroceryOrderLine orderLine = orderLineBySource.get(sourceLine.getId());
      if (orderLine == null) {
        continue;
      }
      LineFulfilmentStatus fulfilment = fulfilmentFor(orderLine.getLineStatus());
      if (fulfilment == null) {
        continue; // QUEUED / unknown — not part of this reconciliation, leave untouched.
      }
      // Never overwrite a line a user already marked bought manually (Tier 2).
      if (sourceLine.getFulfilmentStatus() == LineFulfilmentStatus.BOUGHT) {
        continue;
      }

      applyOrderFulfilment(sourceLine, orderLine, fulfilment, order, now);
      shoppingListDataGateway.saveLine(sourceLine);
      ShoppingList parent = sourceLine.getShoppingList();
      if (parent != null) {
        touchedParents.putIfAbsent(parent.getId(), parent);
      }
    }
    // One version bump per touched parent list (the aggregate-root @Version covers its child
    // lines).
    for (ShoppingList parent : touchedParents.values()) {
      shoppingListDataGateway.touchListVersion(parent);
    }
  }

  /**
   * Map an order line's {@link OrderLineStatus} onto the source-line {@link LineFulfilmentStatus}.
   * Arrived (delivered/added) → {@code BOUGHT}; an accepted substitution ({@code SUBSTITUTED}) →
   * {@code SUBSTITUTED}; a rejected substitution or unavailable line ({@code REJECTED} / {@code
   * UNAVAILABLE}) → {@code DROPPED}. A {@code QUEUED} line (never placed) → {@code null}
   * (untouched).
   */
  private static LineFulfilmentStatus fulfilmentFor(OrderLineStatus status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case DELIVERED, ADDED, ADDED_PARTIAL -> LineFulfilmentStatus.BOUGHT;
      case SUBSTITUTED -> LineFulfilmentStatus.SUBSTITUTED;
      case REJECTED, UNAVAILABLE -> LineFulfilmentStatus.DROPPED;
      case QUEUED -> null;
    };
  }

  /**
   * Apply the order-driven fulfilment fields to a source line. For {@code BOUGHT}/{@code
   * SUBSTITUTED} we record the bought quantity/unit/price; for {@code DROPPED} we record only the
   * status + {@code grocery_order_id} (no bought price — nothing arrived).
   */
  private static void applyOrderFulfilment(
      ShoppingListLine sourceLine,
      GroceryOrderLine orderLine,
      LineFulfilmentStatus fulfilment,
      GroceryOrder order,
      Instant now) {
    sourceLine.setFulfilmentStatus(fulfilment);
    sourceLine.setBoughtVia(BoughtVia.ORDER);
    sourceLine.setGroceryOrderId(order.getId());
    if (fulfilment == LineFulfilmentStatus.DROPPED) {
      sourceLine.setBoughtQuantity(null);
      sourceLine.setBoughtUnit(null);
      sourceLine.setBoughtPricePence(null);
      sourceLine.setBoughtAt(null);
      return;
    }
    sourceLine.setBoughtQuantity(orderLine.getQuantityRequested());
    sourceLine.setBoughtUnit(orderLine.getQuantityUnit());
    sourceLine.setBoughtPricePence(paidUnitPence(orderLine));
    sourceLine.setBoughtAt(now);
  }

  private static com.example.mealprep.provisions.api.dto.GroceryOrderLine toProvisionLine(
      GroceryOrderLine line) {
    String productId =
        line.getProviderProductId() != null
            ? line.getProviderProductId()
            : "grocery:" + line.getIngredientMappingKey();
    return new com.example.mealprep.provisions.api.dto.GroceryOrderLine(
        productId,
        line.getDisplayName(),
        line.getIngredientMappingKey(),
        line.getQuantityRequested(),
        line.getQuantityUnit(),
        pricePounds(paidUnitPence(line)),
        null, // category — provisions defaults it
        line.getPackSizeG());
  }

  private static String supplier(String providerKey) {
    return providerKey != null && !providerKey.isBlank() ? providerKey : "grocery";
  }

  /** Convert integer pence → BigDecimal pounds (scale 2). Null pence → null pounds. */
  private static BigDecimal pricePounds(Integer pence) {
    if (pence == null) {
      return null;
    }
    return BigDecimal.valueOf(pence).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }
}
