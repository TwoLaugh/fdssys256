/**
 * Tier-3 orders panel (groceries.md §5): provider gate, the 11-status order
 * state machine with per-state buttons, order-line tables, cancel-with-reason
 * and the substitution review cards that gate reconciliation.
 */

import { useState } from "react";
import { Modal } from "../../components/Modal";
import { OrderTimeline } from "../../components/OrderTimeline";
import { SwapLine } from "../../components/SwapLine";
import { TintChip } from "../../components/TintChip";
import {
  cancelGroceryOrder,
  createGroceryOrder,
  markOrderDelivered,
  markUserConfirmed,
  placeOrder,
  quoteOrder,
  refreshOrderStatus,
  resolveSubstitution,
  useStore,
} from "../../mock/store";
import type {
  GroceryOrderDto,
  GrocerySubstitutionProposalDto,
} from "../../mock/types";
import {
  fmtSlot,
  fmtWhen,
  money,
  ORDER_LINE_MARK,
  ORDER_STATUS_META,
  ORDER_TIMELINE_STEPS,
  statusReasonCopy,
} from "./shared";

function OrderBanner({
  tone,
  children,
}: {
  tone: "info" | "amber" | "red";
  children: React.ReactNode;
}) {
  return <div className={`order-banner ${tone}`}>{children}</div>;
}

function OpenBasketLink({ order }: { order: GroceryOrderDto }) {
  if (!order.confirmLink) return null;
  return (
    <a
      className="btn btn-small"
      href={order.confirmLink}
      target="_blank"
      rel="noreferrer"
      style={{ textDecoration: "none", display: "inline-block" }}
    >
      Open {order.providerKey} basket ↗
    </a>
  );
}

/** Totals row — show the most advanced non-null stage (§5b). */
function totalsLine(order: GroceryOrderDto): string | null {
  if (order.paidTotalPence != null) return `paid ${money(order.paidTotalPence)}`;
  if (order.confirmedTotalPence != null)
    return `confirmed ${money(order.confirmedTotalPence)}`;
  if (order.quotedTotalPence != null)
    return `quoted ${money(order.quotedTotalPence)}`;
  return null;
}

function OrderLines({ order }: { order: GroceryOrderDto }) {
  return (
    <div style={{ marginTop: 10 }}>
      {order.lines.map((ln) => {
        const mark = ORDER_LINE_MARK[ln.lineStatus];
        const price =
          ln.paidUnitPence ?? ln.confirmedUnitPence ?? ln.quotedUnitPence;
        const deliveredDiffers =
          ln.packCountDelivered != null &&
          ln.packCountRequested != null &&
          ln.packCountDelivered !== ln.packCountRequested;
        return (
          <div key={ln.id} className="order-line-row">
            <span
              className="status-mark"
              style={{ color: mark.color }}
              title={mark.label}
            >
              {mark.glyph}
            </span>
            <span
              style={{
                flex: 1,
                minWidth: 0,
                textDecoration: mark.struck ? "line-through" : undefined,
                color: mark.struck ? "var(--mp-muted)" : undefined,
              }}
              title={ln.providerProductId ?? undefined}
            >
              {ln.displayName}
              <span className="order-line-meta">
                {" "}
                · {ln.quantityRequested} {ln.quantityUnit}
                {ln.packCountRequested != null && ln.packSizeG != null
                  ? ` (${ln.packCountRequested} × ${ln.packSizeG} g)`
                  : ""}
              </span>
              {deliveredDiffers && (
                <span style={{ color: "var(--mp-amber)", fontSize: 12 }}>
                  {" "}
                  · {ln.packCountDelivered} delivered
                </span>
              )}
              {ln.note && <span className="order-line-meta"> · {ln.note}</span>}
            </span>
            <span className="order-line-price">
              {price != null ? money(price) : "—"}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/** Substitution proposal card (§5d) — consequences spelled out (HLD). */
function ProposalCard({
  proposal,
}: {
  proposal: GrocerySubstitutionProposalDto;
}) {
  const actionable =
    proposal.proposalStatus === "PENDING_USER_REVIEW" ||
    proposal.proposalStatus === "UNPARSED";
  return (
    <div className="proposal-card">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
        <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
          Substitution
        </span>
        {proposal.proposalStatus === "UNPARSED" && (
          <TintChip tone="terra">we couldn't read this one — judge it yourself</TintChip>
        )}
        {(proposal.proposalStatus === "ACCEPTED" ||
          proposal.proposalStatus === "REJECTED") && (
          <span className={`mp-chip ${proposal.proposalStatus === "ACCEPTED" ? "olive" : "muted"}`}>
            {proposal.proposalStatus.toLowerCase()}
          </span>
        )}
      </div>
      <div style={{ marginTop: 8 }}>
        <SwapLine
          from={proposal.originalDisplayName}
          to={proposal.substituteDisplayName}
          delta={
            proposal.substituteUnitPence != null
              ? money(proposal.substituteUnitPence)
              : undefined
          }
        />
      </div>
      <div className="order-line-meta" style={{ marginTop: 5 }}>
        {proposal.substituteQuantity != null &&
          `${proposal.substituteQuantity} ${proposal.substituteUnit ?? ""}`}
        {proposal.reason && (
          <>
            {" "}
            · <TintChip>{proposal.reason}</TintChip>
          </>
        )}
        {proposal.resolvedAt && ` · resolved ${fmtWhen(proposal.resolvedAt)}`}
      </div>
      {actionable && (
        <>
          <div className="proposal-consequences">
            Accept → the substitute goes into your pantry. Reject → logged as
            unmet — the planner may suggest re-optimising affected meals.
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
            <button
              className="btn btn-small"
              onClick={() =>
                resolveSubstitution(proposal.groceryOrderId, proposal.id, "REJECTED")
              }
            >
              Reject
            </button>
            <button
              className="btn btn-small btn-primary"
              onClick={() =>
                resolveSubstitution(proposal.groceryOrderId, proposal.id, "ACCEPTED")
              }
            >
              Accept
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function OrderCard({ order }: { order: GroceryOrderDto }) {
  const [expanded, setExpanded] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  const proposals = useStore(
    (s) => s.grocery.proposalsByOrder[order.id],
  );
  const list = useStore((s) =>
    s.grocery.lists.find((l) => l.id === order.shoppingListId),
  );
  const meta = ORDER_STATUS_META[order.status];
  const outstanding = order.outstandingProposals ?? [];
  const addedCount = order.lines.filter(
    (ln) => ln.lineStatus === "ADDED" || ln.lineStatus === "ADDED_PARTIAL",
  ).length;
  const inert = order.status === "ARCHIVED";
  const cancellable = !["RECONCILED", "CANCELLED", "ARCHIVED"].includes(
    order.status,
  );

  const buttons: React.ReactNode[] = [];
  if (!inert) {
    if (order.status === "DRAFT") {
      buttons.push(
        <button key="quote" className="btn btn-small btn-primary" onClick={() => quoteOrder(order.id)}>
          Get quote
        </button>,
      );
    }
    if (order.status === "QUOTED") {
      buttons.push(
        <button key="place" className="btn btn-small btn-primary" onClick={() => placeOrder(order.id)}>
          Place order
        </button>,
      );
    }
    if (order.status === "PROVIDER_UNAVAILABLE") {
      buttons.push(
        <button key="requote" className="btn btn-small btn-primary" onClick={() => quoteOrder(order.id)}>
          Try quote again
        </button>,
      );
    }
    if (
      ["PLACED", "PLACED_PARTIAL", "AWAITING_USER_CONFIRMATION", "CONFIRMED", "DELIVERED"].includes(
        order.status,
      )
    ) {
      buttons.push(
        <button key="refresh" className="btn btn-small" onClick={() => refreshOrderStatus(order.id)}>
          Refresh status
        </button>,
      );
    }
    if (order.status === "AWAITING_USER_CONFIRMATION") {
      buttons.push(
        <button key="confirmed" className="btn btn-small btn-primary" onClick={() => markUserConfirmed(order.id)}>
          I've confirmed
        </button>,
      );
    }
    if (order.status === "CONFIRMED") {
      buttons.push(
        <button key="arrived" className="btn btn-small" onClick={() => markOrderDelivered(order.id)}>
          It arrived
        </button>,
      );
    }
    if (cancellable) {
      buttons.push(
        <button key="cancel" className="btn btn-small" onClick={() => setCancelOpen(true)}>
          Cancel
        </button>,
      );
    }
  }

  return (
    <div className="mp-card order-card">
      <div className="order-card-head">
        <span className="order-provider" style={{ marginTop: 0 }}>
          {order.providerKey}
          {order.providerOrderId && (
            <span className="order-line-meta"> · {order.providerOrderId}</span>
          )}
        </span>
        <span className={`mp-chip ${meta.tone}`}>{meta.label}</span>
      </div>

      {meta.timelineAt != null && (
        <div style={{ marginTop: 14 }}>
          <OrderTimeline steps={ORDER_TIMELINE_STEPS} at={meta.timelineAt} />
        </div>
      )}

      {order.status === "PLACED" && order.statusReason && (
        <OrderBanner tone="amber">
          {statusReasonCopy(order.statusReason, order.providerKey)}
        </OrderBanner>
      )}
      {order.status === "PLACED_PARTIAL" && (
        <OrderBanner tone="amber">
          {addedCount} of {order.lines.length} items added — complete the basket
          manually in {order.providerKey}.
        </OrderBanner>
      )}
      {order.status === "AWAITING_USER_CONFIRMATION" && (
        <OrderBanner tone="info">
          Confirm the order in {order.providerKey} — we never confirm for you.
        </OrderBanner>
      )}
      {order.status === "PROVIDER_UNAVAILABLE" && (
        <OrderBanner tone="red">
          {order.providerKey} unreachable — retrying hourly for 24 h, then
          auto-cancel. You can always export the list and shop manually.
        </OrderBanner>
      )}
      {order.status === "DELIVERED" && outstanding.length > 0 && (
        <OrderBanner tone="amber">
          Resolve {outstanding.length} substitution
          {outstanding.length === 1 ? "" : "s"} to finish — reconciliation runs
          automatically when the last one resolves.
        </OrderBanner>
      )}
      {order.status === "RECONCILED" && (
        <div className="order-eta" style={{ color: "var(--mp-olive)" }}>
          {totalsLine(order)} · pantry updated, prices recorded
        </div>
      )}
      {order.status === "CANCELLED" && (
        <div className="order-eta">
          cancelled {order.cancelledAt && fmtWhen(order.cancelledAt)}
          {order.cancelReason && ` — “${order.cancelReason}”`}
        </div>
      )}

      <div className="order-eta">
        {totalsLine(order) && order.status !== "RECONCILED" && (
          <span>{totalsLine(order)}</span>
        )}
        {order.deliverySlotStart && order.deliverySlotEnd && (
          <span> · slot {fmtSlot(order.deliverySlotStart, order.deliverySlotEnd)}</span>
        )}
        {list && <span> · from the list of {fmtDayShort(list.generatedAt)}</span>}
      </div>
      {order.lastStatusCheckAt && (
        <div className="order-line-meta" style={{ marginTop: 3 }}>
          status checked {fmtWhen(order.lastStatusCheckAt)}
        </div>
      )}

      {order.status === "DELIVERED" &&
        outstanding.map((p) => <ProposalCard key={p.id} proposal={p} />)}
      {order.status === "DELIVERED" && outstanding.length > 0 && (
        <div className="inline-note" style={{ marginTop: 8 }}>
          All substitutions must be resolved before the order completes.
        </div>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: 12, flexWrap: "wrap" }}>
        <OpenBasketLink order={order} />
        {buttons}
        <button
          className="btn btn-small"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
        >
          {expanded ? "Hide lines" : `Lines (${order.lines.length})`}
        </button>
      </div>

      {expanded && (
        <>
          <OrderLines order={order} />
          {(proposals ?? [])
            .filter((p) => p.proposalStatus === "ACCEPTED" || p.proposalStatus === "REJECTED")
            .map((p) => (
              <ProposalCard key={p.id} proposal={p} />
            ))}
        </>
      )}

      {cancelOpen && (
        <Modal label="Cancel order" onClose={() => setCancelOpen(false)}>
          <span className="mp-label">Cancel order</span>
          <p className="dialog-body">
            Cancels the {order.providerKey} order. Anything already in the
            provider basket stays there — clear it in their UI.
          </p>
          <label className="field-label" htmlFor={`cancel-reason-${order.id}`}>
            Reason (optional, ≤ 64)
          </label>
          <input
            id={`cancel-reason-${order.id}`}
            className="text-input"
            style={{ width: "100%" }}
            maxLength={64}
            value={cancelReason}
            onChange={(e) => setCancelReason(e.target.value)}
            placeholder="e.g. plans changed"
          />
          <div style={{ display: "flex", gap: 8, marginTop: 14, justifyContent: "flex-end" }}>
            <button className="btn btn-small" onClick={() => setCancelOpen(false)}>
              Keep order
            </button>
            <button
              className="btn btn-small btn-danger"
              onClick={() => {
                cancelGroceryOrder(order.id, cancelReason);
                setCancelOpen(false);
              }}
            >
              Cancel order
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function fmtDayShort(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  });
}

export function OrdersPanel() {
  const providerState = useStore((s) => s.grocery.providerState);
  const orders = useStore((s) => s.grocery.orders);
  const visible = orders.filter((o) => o.status !== "ARCHIVED");

  // #20 gate: 404 → connect CTA; the list and mark-bought stay fully usable
  // (tiers 1/2/4 are full value without a provider — HLD).
  if (!providerState) {
    return (
      <div className="mp-card order-card">
        <span className="mp-label">Orders</span>
        <div className="order-empty">
          No provider connected — connect Tesco in{" "}
          <a href="/settings">Settings</a>. The list, mark-bought and price
          history work fully without one.
        </div>
      </div>
    );
  }

  const sessionExpired =
    providerState.sessionExpiresAt != null &&
    providerState.sessionExpiresAt < "2026-06-10T18:05:00Z";

  return (
    <div style={{ display: "grid", gap: 14 }}>
      <div className="mp-card order-card">
        <div className="order-card-head">
          <span className="mp-label">Orders · {providerState.providerKey}</span>
          {providerState.enabled ? (
            <button className="btn btn-small btn-primary" onClick={createGroceryOrder}>
              Order via {providerState.providerKey}
            </button>
          ) : (
            <span className="mp-chip muted">paused</span>
          )}
        </div>
        {!providerState.enabled && (
          <div className="order-empty">
            Provider paused — re-enable it in <a href="/settings">Settings</a>.
          </div>
        )}
        {(sessionExpired || providerState.lastFailureReason) && (
          <OrderBanner tone="amber">
            <span
              title={`last login ${providerState.lastLoginAt ?? "—"} · last failure ${providerState.lastFailureAt ?? "—"}`}
            >
              {providerState.providerKey} session needs attention
              {providerState.lastFailureReason && ` — ${providerState.lastFailureReason}`}
              {providerState.consecutiveFailures > 0 &&
                ` (${providerState.consecutiveFailures} consecutive failures)`}
              {" · "}
              <a href="/settings">Settings</a>
            </span>
          </OrderBanner>
        )}
        {providerState.enabled && visible.length === 0 && (
          <div className="order-empty">
            No orders yet — get a quote from the current list.
          </div>
        )}
      </div>
      {visible.map((o) => (
        <OrderCard key={o.id} order={o} />
      ))}
    </div>
  );
}
