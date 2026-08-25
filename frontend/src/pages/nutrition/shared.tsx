/**
 * Shared bits for the four Nutrition tabs: formatting, D6 status marks,
 * source badges, micro key/value editing.
 */

import type {
  IntakeSlotStatus,
  MealSlot,
} from "../../mock/types";

/* ---- formatting ---------------------------------------------------------- */

/** "2026-06-10" → "Wednesday 10 June". */
export function prettyDate(iso: string): string {
  return new Date(`${iso}T12:00:00Z`).toLocaleDateString("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  });
}

/** "2026-06-10" → "Wed 10 Jun". */
export function shortDate(iso: string): string {
  return new Date(`${iso}T12:00:00Z`).toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}

/** ISO date-time → "HH:MM". */
export function shortTime(isoDateTime: string): string {
  return isoDateTime.slice(11, 16);
}

export const fmtKcal = (n: number): string =>
  Math.round(n).toLocaleString("en-GB");

export const fmtG = (n: number): string => {
  const r = Math.round(n * 10) / 10;
  return Number.isInteger(r) ? String(r) : r.toFixed(1);
};

export const MEAL_SLOTS: MealSlot[] = [
  "BREAKFAST",
  "LUNCH",
  "DINNER",
  "SNACKS",
];

export const mealSlotLabel = (slot: MealSlot): string => slot.toLowerCase();

/* ---- badges + marks -------------------------------------------------------- */

export function SourceBadge({ source }: { source: string }) {
  const label =
    source === "OPEN_FOOD_FACTS"
      ? "OFF"
      : source === "USDA"
        ? "USDA"
        : source.toLowerCase().replace(/_/g, " ");
  return <span className="tier-badge">{label}</span>;
}

/** Symbol-redundant slot state chip: ○ pending · ✓ confirmed · ✎ logged · — skipped. */
const STATUS_MARK: Record<
  IntakeSlotStatus,
  { glyph: string; color: string; label: string }
> = {
  PENDING: { glyph: "○", color: "var(--mp-mark-planned)", label: "pending" },
  CONFIRMED: { glyph: "✓", color: "var(--mp-olive)", label: "confirmed" },
  OVERRIDDEN: { glyph: "✎", color: "var(--mp-olive)", label: "overridden" },
  EDITED: { glyph: "✎", color: "var(--mp-olive)", label: "edited" },
  SKIPPED: { glyph: "—", color: "var(--mp-muted)", label: "skipped" },
};

export function SlotStateChip({ status }: { status: IntakeSlotStatus }) {
  const m = STATUS_MARK[status];
  return (
    <span
      style={{
        display: "inline-flex",
        gap: 6,
        alignItems: "baseline",
        fontSize: 12,
        fontWeight: 600,
        color: m.color,
        whiteSpace: "nowrap",
      }}
    >
      <span aria-hidden="true">{m.glyph}</span>
      {m.label}
    </span>
  );
}

/** Compact macro read-out line; kcal included only when provided. */
export function MacroLine({
  calories,
  proteinG,
  carbsG,
  fatG,
  fibreG,
}: {
  calories?: number | null;
  proteinG?: number | null;
  carbsG?: number | null;
  fatG?: number | null;
  fibreG?: number | null;
}) {
  const parts: string[] = [];
  if (calories != null) parts.push(`${fmtKcal(calories)} kcal`);
  parts.push(`P ${fmtG(proteinG ?? 0)}`);
  parts.push(`C ${fmtG(carbsG ?? 0)}`);
  parts.push(`F ${fmtG(fatG ?? 0)}`);
  if (fibreG != null) parts.push(`Fb ${fmtG(fibreG)}`);
  return <div className="macro-line">{parts.join(" · ")}</div>;
}

/* ---- micros ------------------------------------------------------------------ */

export const MICRO_META: Record<string, { label: string; unit: string }> = {
  iron_mg: { label: "Iron", unit: "mg" },
  zinc_mg: { label: "Zinc", unit: "mg" },
  vitamin_b12_mcg: { label: "Vitamin B12", unit: "mcg" },
  vitamin_d_mcg: { label: "Vitamin D", unit: "mcg" },
  omega3_g: { label: "Omega-3", unit: "g" },
  magnesium_mg: { label: "Magnesium", unit: "mg" },
  calcium_mg: { label: "Calcium", unit: "mg" },
  sodium_mg: { label: "Sodium", unit: "mg" },
  saturated_fat_g: { label: "Saturated fat", unit: "g" },
  potassium_mg: { label: "Potassium", unit: "mg" },
  selenium_mcg: { label: "Selenium", unit: "mcg" },
  vitamin_c_mg: { label: "Vitamin C", unit: "mg" },
  vitamin_b6_mg: { label: "Vitamin B6", unit: "mg" },
};

export const microLabel = (key: string): string =>
  MICRO_META[key]?.label ?? key.replace(/_/g, " ");

export const microUnit = (key: string): string => MICRO_META[key]?.unit ?? "";

export interface MicroRow {
  key: string;
  value: string;
}

export function rowsFromMicros(
  m?: Record<string, number> | null,
): MicroRow[] {
  return Object.entries(m ?? {}).map(([key, value]) => ({
    key,
    value: String(value),
  }));
}

export function microsFromRows(rows: MicroRow[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const row of rows) {
    const k = row.key.trim();
    const v = Number.parseFloat(row.value);
    if (k && Number.isFinite(v)) out[k] = v;
  }
  return out;
}

/** Dynamic key/value rows for a micros map (edit + snack advanced expander). */
export function MicroRowsEditor({
  rows,
  onChange,
  idPrefix,
}: {
  rows: MicroRow[];
  onChange: (rows: MicroRow[]) => void;
  idPrefix: string;
}) {
  return (
    <div style={{ display: "grid", gap: 6 }}>
      {rows.map((row, i) => (
        <div key={i} style={{ display: "flex", gap: 8 }}>
          <input
            type="text"
            className="text-input"
            style={{ flex: 1, minWidth: 0, padding: "6px 10px" }}
            placeholder="key, e.g. iron_mg"
            value={row.key}
            aria-label={`${idPrefix} micro key ${i + 1}`}
            onChange={(e) =>
              onChange(
                rows.map((r, ri) =>
                  ri === i ? { ...r, key: e.target.value } : r,
                ),
              )
            }
          />
          <input
            type="number"
            className="text-input num-input"
            placeholder="value"
            value={row.value}
            aria-label={`${idPrefix} micro value ${i + 1}`}
            onChange={(e) =>
              onChange(
                rows.map((r, ri) =>
                  ri === i ? { ...r, value: e.target.value } : r,
                ),
              )
            }
          />
          <button
            className="btn btn-small"
            aria-label={`Remove micro row ${i + 1}`}
            onClick={() => onChange(rows.filter((_, ri) => ri !== i))}
          >
            ✕
          </button>
        </div>
      ))}
      <div>
        <button
          className="btn btn-small"
          onClick={() => onChange([...rows, { key: "", value: "" }])}
        >
          + add micro
        </button>
      </div>
    </div>
  );
}

/* ---- switch -------------------------------------------------------------------- */

export function Switch({
  on,
  onToggle,
  disabled,
  label,
  title,
}: {
  on: boolean;
  onToggle?: () => void;
  disabled?: boolean;
  label: string;
  title?: string;
}) {
  return (
    <button
      className={`switch${on ? " on" : ""}`}
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={disabled}
      title={title}
      onClick={onToggle}
      style={disabled ? { opacity: 0.45, cursor: "not-allowed" } : undefined}
    >
      <span className="switch-knob" />
    </button>
  );
}

/* ---- shared inputs ---------------------------------------------------------------- */

/** Parse a number input; empty string → null; invalid → null. */
export function parseNum(v: string): number | null {
  if (v.trim() === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
