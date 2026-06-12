/**
 * Shared bits for the Groceries page (and money/date helpers the Pantry page
 * reuses): pence formatting, contract status maps, fulfilment marks, source
 * badges (design/frontend/pages/groceries.md §3b, §5c, §6).
 */

import type {
  GroceryOrderStatus,
  LineFulfilmentStatus,
  OrderLineStatus,
  PriceSource,
  ShoppingListLineDto,
} from "../../mock/types";

/* ---- money + dates ---------------------------------------------------------- */

/** 5240 → "£52.40" (integer pence ÷ 100, GBP v1). */
export function money(pence: number): string {
  return `£${(pence / 100).toFixed(2)}`;
}

/** Estimates are approximations — "~£3.20" (no false precision, §6d). */
export function approxMoney(pence: number): string {
  return `~${money(pence)}`;
}

/** "2026-06-09T18:12:00Z" → "Tue 9 June, 18:12". */
export function fmtWhen(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  })}, ${iso.slice(11, 16)}`;
}

/** "2026-06-09T18:12:00Z" → "Tue 9 Jun". */
export function fmtDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}

/** Delivery slot pair → "Fri 12 Jun, 18:00–19:00". */
export function fmtSlot(startIso: string, endIso: string): string {
  return `${fmtDay(startIso)}, ${startIso.slice(11, 16)}–${endIso.slice(11, 16)}`;
}

/** 0.83 → "83% confidence"; null → null. */
export function confidenceLabel(conf: number | null | undefined): string | null {
  return conf == null ? null : `${Math.round(conf * 100)}% confidence`;
}

export function quantityLabel(q: number, unit: string): string {
  return `${q % 1 === 0 ? q : q.toFixed(2)} ${unit}`;
}

/* ---- line fulfilment (§3b display rules) -------------------------------------- */

export const FULFILMENT_MARK: Record<
  LineFulfilmentStatus,
  { glyph: string; color: string; label: string; struck: boolean }
> = {
  UNFILLED: { glyph: "○", color: "var(--mp-mark-planned)", label: "unfilled", struck: false },
  PARTIAL: { glyph: "◐", color: "var(--mp-amber)", label: "partial", struck: false },
  BOUGHT: { glyph: "✓", color: "var(--mp-olive)", label: "bought", struck: true },
  SUBSTITUTED: { glyph: "⇄", color: "var(--mp-olive)", label: "substituted", struck: false },
  DROPPED: { glyph: "—", color: "var(--mp-muted)", label: "dropped", struck: true },
};

export const VIA_LABEL: Record<string, string> = {
  MANUAL: "marked by you",
  BULK_TOTAL: "bulk",
  ORDER: "from order",
};

/** Pack suggestion sub-line: "1 × 1 kg pack"; null parts → null. */
export function packSuggestion(line: ShoppingListLineDto): string | null {
  if (line.suggestedPackCount == null) return null;
  if (line.suggestedPackSizeG != null) {
    const size =
      line.suggestedPackSizeG >= 1000
        ? `${line.suggestedPackSizeG / 1000} kg`
        : `${line.suggestedPackSizeG} g`;
    return `${line.suggestedPackCount} × ${size} pack`;
  }
  if (line.suggestedPackUnit != null) {
    if (line.suggestedPackUnit === "items") {
      return `${line.suggestedPackCount} pack${line.suggestedPackCount === 1 ? "" : "s"}`;
    }
    return `${line.suggestedPackCount} × 1 ${line.suggestedPackUnit} pack`;
  }
  return null;
}

/* ---- order status machine (§5c) -------------------------------------------------- */

/** The happy-path timeline; off-path states map onto their nearest stage. */
export const ORDER_TIMELINE_STEPS = [
  "Draft",
  "Quoted",
  "Placed",
  "Confirmed",
  "Delivered",
  "Reconciled",
];

export const ORDER_STATUS_META: Record<
  GroceryOrderStatus,
  {
    label: string;
    tone: "olive" | "amber" | "red" | "muted";
    /** Index into ORDER_TIMELINE_STEPS; null = no timeline (cancelled). */
    timelineAt: number | null;
  }
> = {
  DRAFT: { label: "Draft", tone: "muted", timelineAt: 0 },
  QUOTED: { label: "Quoted", tone: "olive", timelineAt: 1 },
  PLACED: { label: "Placed — slot needed", tone: "amber", timelineAt: 2 },
  PLACED_PARTIAL: { label: "Placed partial", tone: "amber", timelineAt: 2 },
  AWAITING_USER_CONFIRMATION: {
    label: "Awaiting your confirmation",
    tone: "amber",
    timelineAt: 2,
  },
  CONFIRMED: { label: "Confirmed", tone: "olive", timelineAt: 3 },
  DELIVERED: { label: "Delivered", tone: "olive", timelineAt: 4 },
  RECONCILED: { label: "Reconciled", tone: "muted", timelineAt: 5 },
  CANCELLED: { label: "Cancelled", tone: "muted", timelineAt: null },
  ARCHIVED: { label: "Archived", tone: "muted", timelineAt: 5 },
  PROVIDER_UNAVAILABLE: { label: "Provider unavailable", tone: "red", timelineAt: 0 },
};

/** statusReason → human copy (§5b). */
export function statusReasonCopy(reason: string, providerKey: string): string {
  if (reason === "delivery_slot_required") {
    return `Basket built — pick a delivery slot in the ${providerKey} basket`;
  }
  if (reason.toLowerCase().includes("cost cap")) {
    return "AI paused — use the printable list and mark bought manually";
  }
  return reason;
}

/* ---- order line status (§5b) ------------------------------------------------------ */

export const ORDER_LINE_MARK: Record<
  OrderLineStatus,
  { glyph: string; color: string; label: string; struck: boolean }
> = {
  QUEUED: { glyph: "○", color: "var(--mp-mark-planned)", label: "queued", struck: false },
  ADDED: { glyph: "✓", color: "var(--mp-ink)", label: "added", struck: false },
  ADDED_PARTIAL: { glyph: "◐", color: "var(--mp-amber)", label: "partial", struck: false },
  UNAVAILABLE: { glyph: "✗", color: "var(--mp-muted)", label: "unavailable", struck: false },
  SUBSTITUTED: { glyph: "⇄", color: "var(--mp-amber)", label: "substituted", struck: false },
  DELIVERED: { glyph: "✓", color: "var(--mp-olive)", label: "delivered", struck: false },
  REJECTED: { glyph: "—", color: "var(--mp-muted)", label: "rejected", struck: true },
};

/* ---- price source badges (§6a) ------------------------------------------------------ */

export const PRICE_SOURCE_LABEL: Record<PriceSource, string> = {
  PAID: "paid",
  QUOTE: "quote",
  MANUAL: "manual",
  MANUAL_ESTIMATED: "bulk est.",
  INFLATION_INDEXED: "indexed",
};

/** Confidence dot: amber < 0.5, olive ≥ 0.5 (§6d). */
export function ConfDot({ value, title }: { value: number; title?: string }) {
  return (
    <span
      title={title ?? `${Math.round(value * 100)}% confidence`}
      style={{
        display: "inline-block",
        width: 7,
        height: 7,
        borderRadius: "50%",
        background: value < 0.5 ? "var(--mp-amber)" : "var(--mp-olive)",
        flexShrink: 0,
      }}
    />
  );
}

/* ---- browser-side export behaviours (§3c) ----------------------------------------- */

export function downloadFile(name: string, content: string, mime: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export function openPrintWindow(html: string): void {
  const w = window.open("", "_blank", "width=720,height=900");
  if (!w) return;
  w.document.write(
    `<html><head><title>Shopping list</title></head><body>${html}</body></html>`,
  );
  w.document.close();
  w.print();
}
