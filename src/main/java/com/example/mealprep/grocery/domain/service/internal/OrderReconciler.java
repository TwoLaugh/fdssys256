package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.PriceSource;
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
import java.util.List;
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
  private final OrderStateMachine stateMachine;
  private final PriceObservationWriter priceObservationWriter;
  private final ProvisionUpdateService provisionUpdateService;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  OrderReconciler(
      GroceryOrderDataGateway dataGateway,
      OrderStateMachine stateMachine,
      PriceObservationWriter priceObservationWriter,
      ProvisionUpdateService provisionUpdateService,
      org.springframework.context.ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.dataGateway = dataGateway;
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
