import { PageHeader } from "../components/PageHeader";
import { SegmentBar } from "../components/SegmentBar";
import { StatStrip } from "../components/StatStrip";
import { MOCK_TODAY_ISO } from "../mock/seed";
import { adjustPantryQty, markSpoiled, useStore } from "../mock/store";
import type { PantryItem, PantryLocation } from "../mock/types";

const LOCATIONS: Array<{ key: PantryLocation; label: string }> = [
  { key: "fridge", label: "Fridge" },
  { key: "freezer", label: "Freezer" },
  { key: "pantry", label: "Pantry" },
];

const DAY_MS = 24 * 60 * 60 * 1000;

function daysUntil(expiryIso: string): number {
  return Math.round(
    (Date.parse(expiryIso) - Date.parse(MOCK_TODAY_ISO)) / DAY_MS,
  );
}

function expiryLabel(expiryIso: string): string {
  const d = new Date(expiryIso);
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

function expiryColor(days: number): string {
  if (days <= 3) return "var(--mp-red)";
  if (days <= 7) return "var(--mp-amber)";
  return "var(--mp-muted)";
}

function PantryRow({ item }: { item: PantryItem }) {
  const days = daysUntil(item.expiry);
  return (
    <div className="pantry-row">
      <div style={{ flex: 1, minWidth: 0 }}>
        <span className={`pantry-name${item.spoiled ? " spoiled" : ""}`}>
          {item.name}
        </span>
        {item.spoiled && <span className="spoiled-tag">SPOILED</span>}
      </div>
      {item.spoiled ? (
        <span className="pantry-qty" />
      ) : (
        <span className="pantry-stepper">
          <button
            className="stepper-btn"
            aria-label={`Decrease ${item.name} quantity`}
            onClick={() => adjustPantryQty(item.id, -1)}
            disabled={item.qty <= 0}
          >
            −
          </button>
          <span className="pantry-qty">
            {item.qty}
            {item.unit ? ` ${item.unit}` : ""}
          </span>
          <button
            className="stepper-btn"
            aria-label={`Increase ${item.name} quantity`}
            onClick={() => adjustPantryQty(item.id, 1)}
          >
            +
          </button>
        </span>
      )}
      <span
        className="pantry-expiry"
        style={{ color: item.spoiled ? "var(--mp-muted)" : expiryColor(days) }}
      >
        {expiryLabel(item.expiry)}
      </span>
      <span className="pantry-action">
        {!item.spoiled && (
          <button
            className="btn btn-small"
            onClick={() => markSpoiled(item.id)}
          >
            Mark spoiled
          </button>
        )}
      </span>
    </div>
  );
}

export function Pantry() {
  const pantry = useStore((s) => s.pantry);

  const live = pantry.items.filter((it) => !it.spoiled);
  const expiringSoon = live.filter((it) => daysUntil(it.expiry) <= 7).length;
  const headroom = pantry.budget.total - pantry.budget.spent;

  return (
    <div>
      <PageHeader
        title="Pantry"
        meta="Fridge, freezer and cupboard inventory · spoilage feeds the plan"
      />

      <div style={{ marginTop: 24 }}>
        <StatStrip
          numeralSize={22}
          cells={[
            { label: "Items tracked", value: String(live.length) },
            {
              label: "Expiring soon",
              value: String(expiringSoon),
              sub: "within 7 days",
              warn: expiringSoon > 0,
            },
            {
              label: "Waste this month",
              value: `£${pantry.waste.monthTotal.toFixed(2)}`,
              sub: `${pantry.waste.entries.length} items logged`,
            },
            {
              label: "Budget headroom",
              value: `£${headroom.toFixed(2)}`,
              sub: `vs £${pantry.budget.total} weekly`,
            },
          ]}
        />
      </div>

      <div className="pantry-layout">
        <div>
          {LOCATIONS.map(({ key, label }) => {
            const items = pantry.items.filter((it) => it.location === key);
            if (items.length === 0) return null;
            return (
              <div key={key} style={{ marginBottom: 22 }}>
                <div className="group-head">
                  <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
                    {label}
                  </span>
                </div>
                {items.map((item) => (
                  <PantryRow key={item.id} item={item} />
                ))}
              </div>
            );
          })}
          <div className="grocery-footnote">
            Marking an item spoiled logs its cost to waste and asks the planner
            for a fix — eaten and cooked meals stay pinned.
          </div>
        </div>

        <div style={{ display: "grid", gap: 18, alignContent: "start" }}>
          <div className="mp-card side-card">
            <span className="mp-label">Week budget</span>
            <div className="budget-row">
              <span className="mp-num" style={{ fontSize: 22 }}>
                £{pantry.budget.spent.toFixed(2)}
              </span>
              <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
                of £{pantry.budget.total}
              </span>
            </div>
            <div style={{ marginTop: 10 }}>
              <SegmentBar pct={pantry.budget.spent / pantry.budget.total} />
            </div>
            <div className="budget-note">{pantry.budget.note}</div>
          </div>

          <div className="mp-card side-card">
            <span className="mp-label">Waste this month</span>
            <div style={{ marginTop: 10 }}>
              {pantry.waste.entries.map((entry) => (
                <div key={`${entry.name}-${entry.when}`} className="waste-row">
                  <span style={{ minWidth: 0 }}>{entry.name}</span>
                  <span className="waste-meta">
                    {entry.cost} · {entry.when}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="mp-card side-card">
            <span className="mp-label">Equipment</span>
            <div className="equipment-list">
              {pantry.equipment.map((eq) => (
                <span key={eq} className="detail-chip">
                  {eq}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
