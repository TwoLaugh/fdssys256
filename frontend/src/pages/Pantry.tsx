/**
 * Pantry — the provisions module's user surface, rebuilt on the production
 * contract (design/frontend/pages/pantry.md): inventory with the two
 * tracking modes + lifecycle actions, waste log + summary, equipment,
 * weekly budget and the supplier price book.
 */

import { useMemo, useState } from "react";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { TintChip } from "../components/TintChip";
import { MOCK_TODAY_ISO } from "../live/dates";
import {
  adjustItemQuantity,
  consumePortions,
  cycleStapleStatus,
  markItemExhausted,
  markSpoiled,
  recipeName,
  removeEquipment,
  removeInventoryItem,
  saveBudget,
  upsertEquipment,
  useStore,
  wasteSummaryFor,
} from "../mock/store";
import type {
  AuditActor,
  InventoryItemDto,
  ItemSource,
  PriceSensitivity,
  StorageLocation,
  SupplierProductDto,
} from "../mock/types";
import { fmtWhen, money } from "./groceries/shared";
import { Switch } from "./nutrition/shared";
import { ItemForm } from "./pantry/ItemForm";
import { WasteForm } from "./pantry/WasteForm";

/* ---- expiry colour rules (§3b) ------------------------------------------------------ */

const DAY_MS = 24 * 60 * 60 * 1000;

function daysUntil(expiryIso: string): number {
  return Math.round((Date.parse(expiryIso) - Date.parse(MOCK_TODAY_ISO)) / DAY_MS);
}

function expiryLabel(expiryIso: string): string {
  return new Date(expiryIso).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
  });
}

/** ≤ 2 days red, ≤ 7 amber, else muted; freezer rows use a 14-day amber. */
function expiryColor(days: number, freezer: boolean): string {
  if (days <= 2) return "var(--mp-red)";
  if (days <= (freezer ? 14 : 7)) return "var(--mp-amber)";
  return "var(--mp-muted)";
}

const SOURCE_LABEL: Record<ItemSource, string> = {
  TESCO_ORDER: "tesco order",
  OTHER_SHOP: "other shop",
  MANUAL_ADD: "manual",
  BATCH_COOK: "batch cook",
  GIFT: "gift",
};

const ACTOR_LABEL: Record<AuditActor, string> = {
  USER: "you",
  COOK_EVENT: "cooking",
  GROCERY_IMPORT: "delivery",
  NUTRITION_LOGGER: "food log",
  SYSTEM: "system",
};

const LOCATIONS: Array<{ key: StorageLocation; label: string }> = [
  { key: "FRIDGE", label: "Fridge" },
  { key: "FREEZER", label: "Freezer" },
  { key: "CUPBOARD", label: "Cupboard" },
  { key: "SPICE_RACK", label: "Spice rack" },
];

/** Stepper step: fine-grained for weights/volumes, unit steps otherwise. */
function stepFor(unit: string | null | undefined): number {
  return unit === "g" || unit === "ml" ? 50 : 1;
}

function approxQty(item: InventoryItemDto): string {
  return `~${item.quantity ?? 0}${item.unit ? ` ${item.unit}` : ""}`;
}

/* ---- spoil confirm (the cross-module promise copy, §3e) ----------------------------- */

function SpoilConfirm({
  item,
  onClose,
}: {
  item: InventoryItemDto;
  onClose: () => void;
}) {
  const [alsoWaste, setAlsoWaste] = useState(false);
  return (
    <Modal label={`Mark ${item.name} spoiled`} onClose={onClose}>
      <span className="mp-label" style={{ color: "var(--mp-red)" }}>
        Mark spoiled
      </span>
      <p className="dialog-body">
        Marks {item.name} spoiled and removes it from your pantry. The planner
        will offer to re-plan any meal that uses it — eaten and cooked meals
        stay pinned. <em>This doesn't log waste</em> — tick below to also log
        the cost (a separate call that can fail independently).
      </p>
      <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13.5 }}>
        <input
          type="checkbox"
          checked={alsoWaste}
          onChange={(e) => setAlsoWaste(e.target.checked)}
        />
        Also log to waste
        {item.costPaid != null && ` (£${item.costPaid.toFixed(2)})`}
      </label>
      <div style={{ display: "flex", gap: 8, marginTop: 14, justifyContent: "flex-end" }}>
        <button className="btn btn-small" onClick={onClose}>
          Keep it
        </button>
        <button
          className="btn btn-small btn-danger"
          onClick={() => {
            markSpoiled(item.id, alsoWaste);
            onClose();
          }}
        >
          Mark spoiled
        </button>
      </div>
    </Modal>
  );
}

/* ---- detail drawer (§3b detail home + §3f history + §7 known products) ---------------- */

function DetailDrawer({
  itemId,
  onClose,
  onEdit,
  onLogWaste,
}: {
  itemId: string;
  onClose: () => void;
  onEdit: () => void;
  onLogWaste: () => void;
}) {
  const item = useStore((s) => s.pantry.items.find((it) => it.id === itemId));
  const audit = useStore((s) => s.pantry.auditByItem[itemId]);
  const products = useStore((s) => s.pantry.supplierProducts);
  const recipes = useStore((s) => s.recipes);
  const [tab, setTab] = useState<"Detail" | "History" | "Known products">("Detail");
  if (!item) return null;
  const known = products.filter(
    (p) =>
      item.ingredientMappingKey != null &&
      p.ingredientMappingKey === item.ingredientMappingKey,
  );
  return (
    <Modal label={`${item.name} detail`} onClose={onClose} wide>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">{item.name}</span>
        <button className="btn btn-small" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="nutri-tabs" role="tablist" aria-label="Item detail views">
        {(["Detail", "History", "Known products"] as const).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`filter-chip${tab === t ? " active" : ""}`}
            onClick={() => setTab(t)}
          >
            {t}
          </button>
        ))}
      </div>

      {tab === "Detail" && (
        <div style={{ marginTop: 10, fontSize: 13.5, display: "grid", gap: 6 }}>
          <div>
            <span className="order-line-meta">category · </span>
            {item.category}
            <span className="order-line-meta"> · source · </span>
            {SOURCE_LABEL[item.source]}
            {item.sourceRef && (
              <span className="order-line-meta" title={item.sourceRef}>
                {" "}
                ({item.sourceRef})
              </span>
            )}
          </div>
          <div>
            <span className="order-line-meta">mapping key · </span>
            {item.ingredientMappingKey ?? (
              <span style={{ color: "var(--mp-amber)" }}>
                not matched to nutrition data — edit to link it
              </span>
            )}
          </div>
          {item.costPaid != null && (
            <div>
              <span className="order-line-meta">cost paid · </span>£
              {item.costPaid.toFixed(2)}
            </div>
          )}
          {item.freezerExtension && (
            <div>
              <span className="order-line-meta">freezer · </span>
              {item.freezerExtension.frozenAt &&
                `frozen ${expiryLabel(item.freezerExtension.frozenAt)}`}
              {item.freezerExtension.maxFreezeWeeks != null &&
                ` · keeps ${item.freezerExtension.maxFreezeWeeks} weeks`}
              {item.freezerExtension.defrostMethod &&
                ` · ${item.freezerExtension.defrostMethod.toLowerCase().replace(/_/g, " ")}`}
              {item.freezerExtension.defrostLeadTimeHours != null &&
                item.freezerExtension.defrostLeadTimeHours > 0 &&
                ` · needs ${item.freezerExtension.defrostLeadTimeHours} h defrost`}
              {item.freezerExtension.sourceRecipeId &&
                ` · batch-cooked ${recipeName(recipes, item.freezerExtension.sourceRecipeId)}`}
            </div>
          )}
          {item.notes && <div style={{ fontStyle: "italic" }}>{item.notes}</div>}
          <div className="order-line-meta">
            added {expiryLabel(item.createdAt.slice(0, 10))} · updated{" "}
            {fmtWhen(item.updatedAt)}
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
            <button className="btn btn-small" onClick={onEdit}>
              Edit item
            </button>
            <button className="btn btn-small" onClick={onLogWaste}>
              Log waste
            </button>
            <button
              className="btn btn-small"
              title="Removes without logging waste — for entry mistakes"
              onClick={() => {
                removeInventoryItem(item.id);
                onClose();
              }}
            >
              Remove
            </button>
          </div>
        </div>
      )}

      {tab === "History" && (
        <div style={{ marginTop: 8 }}>
          {(audit ?? []).length === 0 && (
            <div className="order-empty">No recorded changes yet.</div>
          )}
          {(audit ?? []).map((entry) => (
            <div key={entry.id} className="obs-row">
              <span className="tier-badge">{ACTOR_LABEL[entry.actor]}</span>
              <span style={{ flex: 1 }}>
                {entry.fieldChanged}:{" "}
                <span className="order-line-meta">
                  {String(entry.previousValue ?? "—")} → {String(entry.newValue ?? "—")}
                </span>
              </span>
              <span className="order-line-meta">{fmtWhen(entry.occurredAt)}</span>
            </div>
          ))}
          <div className="inline-note" style={{ marginTop: 8 }}>
            Every override is logged with a timestamp — no approval flows.
          </div>
        </div>
      )}

      {tab === "Known products" && (
        <div style={{ marginTop: 8 }}>
          {known.length === 0 ? (
            <div className="order-empty">
              No supplier products cached for this mapping key.
            </div>
          ) : (
            known.map((p) => <SupplierProductRow key={p.id} product={p} />)
          )}
        </div>
      )}
    </Modal>
  );
}

/* ---- supplier product row (§7 price book) ------------------------------------------- */

function freshnessTag(lastChecked: string): React.ReactNode {
  const days = -daysUntil(lastChecked);
  if (days > 28) {
    return (
      <span className="mp-chip muted">too old for cost estimates</span>
    );
  }
  if (days > 14) {
    return <span className="stale-tag">estimated</span>;
  }
  return null;
}

function SupplierProductRow({ product }: { product: SupplierProductDto }) {
  return (
    <div style={{ padding: "8px 0", borderBottom: "1px solid var(--mp-line)" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "baseline", flexWrap: "wrap" }}>
        <span style={{ fontWeight: 600, fontSize: 13.5 }} title={product.productId}>
          {product.name}
        </span>
        <span className="tier-badge">{product.supplier}</span>
        {product.category && <TintChip>{product.category}</TintChip>}
        {freshnessTag(product.lastChecked)}
      </div>
      <div className="order-line-meta" style={{ marginTop: 3 }}>
        {product.price != null && `£${product.price.toFixed(2)}`}
        {product.pricePerUnit != null &&
          ` · ${Math.round(product.pricePerUnit * 100)}p / ${product.unit ?? "unit"}`}
        {product.clubcardPrice != null &&
          ` · £${product.clubcardPrice.toFixed(2)} with Clubcard`}
        {product.packSizeG != null &&
          ` · ${product.packSizeG >= 1000 ? `${product.packSizeG / 1000} kg` : `${product.packSizeG} g`}`}
        {` · checked ${expiryLabel(product.lastChecked)}`}
      </div>
      {product.substitutionHistory.map((sub) => (
        <div key={`${sub.date}-${sub.substitutedWithProductId}`} className="order-line-meta">
          {sub.accepted ? "✓" : "✗"} substitution {expiryLabel(sub.date)} →{" "}
          {sub.substitutedWithProductId}
          {sub.notes && ` — ${sub.notes}`}
        </div>
      ))}
    </div>
  );
}

/* ---- one inventory row (§3b) ---------------------------------------------------------- */

function PantryRow({
  item,
  onDetail,
  onSpoil,
}: {
  item: InventoryItemDto;
  onDetail: () => void;
  onSpoil: () => void;
}) {
  const recipes = useStore((s) => s.recipes);
  const days = item.expiryDate != null ? daysUntil(item.expiryDate) : null;
  const freezer = item.storageLocation === "FREEZER";
  const step = stepFor(item.unit);
  return (
    <div className="pantry-row2">
      <div className="pantry-row-top">
      <div style={{ flex: 1, minWidth: 0 }}>
        <span className="pantry-name" style={{ whiteSpace: "nowrap" }}>
          {item.name}
        </span>{" "}
        <span
          className="order-line-meta"
          style={{ display: "inline-block", whiteSpace: "nowrap" }}
        >
          · {item.category}
        </span>
        {item.isStaple && (
          <span
            style={{ marginLeft: 8 }}
            title="auto-added to the shop when low or out"
          >
            <TintChip>staple</TintChip>
          </span>
        )}
        <span className="tier-badge" style={{ marginLeft: 8 }} title={item.sourceRef ?? undefined}>
          {SOURCE_LABEL[item.source]}
        </span>
      </div>

      {item.trackingMode === "QUANTITY" ? (
        <span className="pantry-stepper">
          <button
            className="stepper-btn"
            aria-label={`Decrease ${item.name} quantity`}
            disabled={(item.quantity ?? 0) <= 0}
            onClick={() =>
              // Absolute semantics: current − step → newQuantity (+ version).
              adjustItemQuantity(item.id, Math.max(0, (item.quantity ?? 0) - step))
            }
          >
            −
          </button>
          <span className="pantry-qty" title="approximate — the system says so when uncertain">
            {approxQty(item)}
          </span>
          <button
            className="stepper-btn"
            aria-label={`Increase ${item.name} quantity`}
            onClick={() => adjustItemQuantity(item.id, (item.quantity ?? 0) + step)}
          >
            +
          </button>
        </span>
      ) : (
        <button
          className="status-chip-btn"
          onClick={() => cycleStapleStatus(item.id)}
          title="Tap cycles stocked → low → out (rides the full item PUT — no focused status endpoint)"
        >
          {item.status === "STOCKED" && <TintChip>● stocked</TintChip>}
          {item.status === "LOW" && (
            <span className="tint-chip amber">◐ low</span>
          )}
          {item.status === "OUT" && <span className="tint-chip red">○ out</span>}
        </button>
      )}

      <span
        className="pantry-expiry"
        style={{
          color: days != null ? expiryColor(days, freezer) : "var(--mp-line-hi)",
        }}
        title={days != null ? `${days} days away` : "no expiry tracked"}
      >
        {item.expiryDate != null ? expiryLabel(item.expiryDate) : ""}
      </span>

      <span className="pantry-action" style={{ width: "auto" }}>
        <span style={{ display: "inline-flex", gap: 6 }}>
          <button
            className="btn btn-small"
            onClick={() => markItemExhausted(item.id)}
            title={
              item.isStaple
                ? "Marks it finished — staples get added to your next shopping list"
                : "Marks it finished"
            }
          >
            Used up
          </button>
          <button className="btn btn-small" onClick={onSpoil}>
            Spoiled
          </button>
          <button className="btn btn-small" onClick={onDetail} aria-label={`${item.name} details`}>
            ⋯
          </button>
        </span>
      </span>
      </div>

      {(item.freezerExtension != null ||
        (item.source === "BATCH_COOK" && item.trackingMode === "QUANTITY")) && (
        <div className="pantry-row-sub order-line-meta">
          {item.source === "BATCH_COOK" && item.trackingMode === "QUANTITY" && (
            <button
              className="btn btn-small"
              style={{ marginRight: 10 }}
              onClick={() => consumePortions(item.id, 1)}
              title="Meal consumption: deducts one portion from the pantry — log the meal on Nutrition separately"
            >
              Ate a portion
            </button>
          )}
          {item.freezerExtension && (
            <>
              {item.freezerExtension.frozenAt &&
                `frozen ${expiryLabel(item.freezerExtension.frozenAt)}`}
              {item.freezerExtension.maxFreezeWeeks != null &&
                ` · keeps ${item.freezerExtension.maxFreezeWeeks} wks`}
              {item.freezerExtension.defrostMethod &&
                ` · ${item.freezerExtension.defrostMethod.toLowerCase().replace(/_/g, " ")}`}
              {item.freezerExtension.defrostLeadTimeHours != null &&
                item.freezerExtension.defrostLeadTimeHours > 0 &&
                ` · ${item.freezerExtension.defrostLeadTimeHours} h defrost`}
              {item.freezerExtension.sourceRecipeId &&
                ` · batch-cooked ${recipeName(recipes, item.freezerExtension.sourceRecipeId)}`}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/* ---- budget card (§6) ------------------------------------------------------------------ */

function BudgetCard() {
  const budget = useStore((s) => s.pantry.budget);
  const [editing, setEditing] = useState(false);
  const [target, setTarget] = useState("");
  const [tolerance, setTolerance] = useState("");
  const [sensitivity, setSensitivity] = useState<PriceSensitivity>("moderate");
  const [enabled, setEnabled] = useState(true);

  const openForm = () => {
    setTarget(budget != null ? String(budget.weeklyTarget) : "");
    setTolerance(budget != null ? String(budget.toleranceOver) : "0");
    setSensitivity(budget?.priceSensitivity ?? "moderate");
    setEnabled(budget?.enabled ?? true);
    setEditing(true);
  };

  return (
    <div className="mp-card side-card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">Weekly budget</span>
        <button className="btn btn-small" onClick={openForm}>
          {budget ? "Edit" : "Set a weekly budget"}
        </button>
      </div>
      {budget == null ? (
        // GET 404 — the set-budget empty state, not an error.
        <div className="order-empty">
          Optional — plans work without it; with it, the planner optimises
          cost.
        </div>
      ) : !budget.enabled ? (
        <div className="order-empty">
          Budget tracking off — the planner stops cost-gating.
        </div>
      ) : (
        <>
          <div className="budget-row">
            <span className="mp-num" style={{ fontSize: 22 }}>
              £{budget.weeklyTarget}
            </span>
            <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
              {budget.currency} weekly
            </span>
          </div>
          <div className="order-line-meta" style={{ marginTop: 6 }}>
            soft ceiling +£{budget.toleranceOver} · chases cheaper options:{" "}
            {budget.priceSensitivity}
          </div>
          <div className="inline-note" style={{ marginTop: 6 }}>
            Spend-vs-target arrives in v1.5 — the contract's spendTracking is
            null in v1. Weekly cost projection lives on Groceries.
          </div>
        </>
      )}

      {editing && (
        <Modal label="Weekly budget" onClose={() => setEditing(false)}>
          <span className="mp-label">Weekly budget</span>
          <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
            <div className="rf-grid2">
              <div>
                <label className="field-label" htmlFor="bf-target">
                  Target (per week) *
                </label>
                <input
                  id="bf-target"
                  type="number"
                  min={0.01}
                  step="1"
                  className="text-input"
                  style={{ width: "100%" }}
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                />
                <div className="inline-note" style={{ marginTop: 3 }}>
                  no upper limit — the system doesn't judge spending
                </div>
              </div>
              <div>
                <label className="field-label" htmlFor="bf-currency">
                  Currency
                </label>
                <input
                  id="bf-currency"
                  className="text-input"
                  style={{ width: "100%" }}
                  value={budget?.currency ?? "GBP"}
                  disabled={budget != null}
                  readOnly={budget != null}
                  title={
                    budget != null
                      ? "Currency can't change on an existing budget (422)"
                      : undefined
                  }
                />
                {budget != null && (
                  <div className="inline-note" style={{ marginTop: 3 }}>
                    fixed after creation (422 on change)
                  </div>
                )}
              </div>
            </div>
            <div>
              <label className="field-label" htmlFor="bf-tolerance">
                Tolerance over (£)
              </label>
              <input
                id="bf-tolerance"
                type="number"
                min={0}
                className="text-input"
                value={tolerance}
                onChange={(e) => setTolerance(e.target.value)}
              />
              <div className="inline-note" style={{ marginTop: 3 }}>
                soft ceiling — a £50 target with £10 tolerance lets a £58 plan
                through
              </div>
            </div>
            <div>
              <span className="field-label">Price sensitivity</span>
              <div style={{ display: "flex", gap: 8 }}>
                {(["low", "moderate", "high"] as PriceSensitivity[]).map((lvl) => (
                  <button
                    key={lvl}
                    className={`filter-chip${sensitivity === lvl ? " active" : ""}`}
                    onClick={() => setSensitivity(lvl)}
                  >
                    {lvl}
                  </button>
                ))}
              </div>
              <div className="inline-note" style={{ marginTop: 3 }}>
                how hard to chase cheaper options
              </div>
            </div>
            <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
              <Switch
                on={enabled}
                onToggle={() => setEnabled((v) => !v)}
                label="Budget enabled"
              />
              <span style={{ fontSize: 13.5 }}>
                Enabled — off stops the planner cost-gating
              </span>
            </div>
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <button className="btn btn-small" onClick={() => setEditing(false)}>
                Cancel
              </button>
              <button
                className="btn btn-small btn-primary"
                onClick={() => {
                  const t = Number(target);
                  if (!Number.isFinite(t) || t <= 0) return;
                  const ok = saveBudget({
                    weeklyTarget: t,
                    currency: budget?.currency ?? "GBP",
                    toleranceOver: tolerance === "" ? 0 : Number(tolerance),
                    priceSensitivity: sensitivity,
                    enabled,
                    expectedVersion: budget?.version ?? 0,
                  });
                  if (ok) setEditing(false);
                }}
              >
                Save budget
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ---- equipment card (§5) ----------------------------------------------------------------- */

const STARTER_EQUIPMENT = ["oven", "hob", "microwave", "kettle", "blender"];

function EquipmentCard() {
  const equipment = useStore((s) => s.pantry.equipment);
  const [newName, setNewName] = useState("");
  const [nameError, setNameError] = useState(false);
  const [detailDrafts, setDetailDrafts] = useState<Record<string, string>>({});

  const pretty = (name: string): string =>
    name.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());

  return (
    <div className="mp-card side-card">
      <span className="mp-label">Equipment</span>
      {equipment.length === 0 ? (
        <div style={{ marginTop: 10 }}>
          <div className="order-empty">
            No list yet? The planner assumes a typical kitchen. Tick what you
            own:
          </div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
            {STARTER_EQUIPMENT.map((name) => (
              <button
                key={name}
                className="filter-chip"
                onClick={() => upsertEquipment(name, { available: true, details: null })}
              >
                + {pretty(name)}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 6 }}>
          {equipment.map((eq) => (
            <div key={eq.name} className="equipment-row">
              <Switch
                on={eq.available}
                onToggle={() =>
                  upsertEquipment(eq.name, {
                    available: !eq.available,
                    details: eq.details ?? null,
                    expectedVersion: eq.version,
                  })
                }
                label={`${pretty(eq.name)} available`}
              />
              <span
                style={{
                  fontSize: 13.5,
                  fontWeight: 600,
                  color: eq.available ? "var(--mp-ink)" : "var(--mp-muted)",
                  width: 110,
                }}
              >
                {pretty(eq.name)}
              </span>
              <input
                className="text-input equipment-details"
                placeholder="details"
                maxLength={255}
                value={detailDrafts[eq.name] ?? eq.details ?? ""}
                onChange={(e) =>
                  setDetailDrafts((d) => ({ ...d, [eq.name]: e.target.value }))
                }
                onBlur={() => {
                  const draft = detailDrafts[eq.name];
                  if (draft !== undefined && draft !== (eq.details ?? "")) {
                    upsertEquipment(eq.name, {
                      available: eq.available,
                      details: draft === "" ? null : draft,
                      expectedVersion: eq.version,
                    });
                  }
                }}
                aria-label={`${pretty(eq.name)} details`}
              />
              <button
                className="chip-x"
                aria-label={`Remove ${pretty(eq.name)}`}
                onClick={() => removeEquipment(eq.name)}
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      )}
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <input
          className="text-input"
          style={{
            flex: 1,
            minWidth: 0,
            padding: "6px 10px",
            borderColor: nameError ? "var(--mp-red)" : undefined,
          }}
          placeholder="add: snake_case, e.g. air_fryer"
          value={newName}
          onChange={(e) => {
            setNewName(e.target.value);
            setNameError(false);
          }}
          aria-label="New equipment name"
        />
        <button
          className="btn btn-small"
          onClick={() => {
            if (!/^[a-z0-9_]+$/.test(newName)) {
              setNameError(true);
              return;
            }
            if (upsertEquipment(newName, { available: true, details: null })) {
              setNewName("");
            }
          }}
        >
          Add
        </button>
      </div>
      {nameError && (
        <div style={{ color: "var(--mp-red)", fontSize: 12, marginTop: 4 }}>
          400 — canonical snake_case names only (a–z, 0–9, _)
        </div>
      )}
      <div className="inline-note" style={{ marginTop: 8 }}>
        Equipment filters which recipes the planner can pick. Unavailable rows
        stay listed — own-but-broken ≠ absent.
      </div>
    </div>
  );
}

/* ---- waste card (§4) ----------------------------------------------------------------------- */

function WasteCard({ onLogWaste }: { onLogWaste: () => void }) {
  const waste = useStore((s) => s.pantry.waste);
  const fullState = useStore((s) => s);
  const [from, setFrom] = useState("2026-03-12"); // default: last 90 days
  const [to, setTo] = useState(MOCK_TODAY_ISO);
  const rangeInvalid = from > to;
  const summary = useMemo(
    () => (rangeInvalid ? null : wasteSummaryFor(fullState, from, to)),
    [fullState, from, to, rangeInvalid],
  );
  const rows = waste.filter((w) => w.occurredOn >= from && w.occurredOn <= to);
  const maxReason = summary
    ? Math.max(1, ...Object.values(summary.countByReason))
    : 1;

  return (
    <div className="mp-card side-card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">Waste</span>
        <button className="btn btn-small" onClick={onLogWaste}>
          Log waste
        </button>
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 10, alignItems: "center" }}>
        <input
          type="date"
          className="text-input"
          style={{ padding: "5px 8px", fontSize: 12.5 }}
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          aria-label="Waste range from"
        />
        <span className="order-line-meta">→</span>
        <input
          type="date"
          className="text-input"
          style={{ padding: "5px 8px", fontSize: 12.5 }}
          value={to}
          onChange={(e) => setTo(e.target.value)}
          aria-label="Waste range to"
        />
      </div>
      {rangeInvalid ? (
        <div style={{ color: "var(--mp-red)", fontSize: 12.5, marginTop: 8 }}>
          400 — "from" must be before "to"; swap the dates.
        </div>
      ) : (
        summary && (
          <>
            <div className="budget-row">
              <span className="mp-num" style={{ fontSize: 22 }}>
                {money(Math.round(summary.totalCostEstimate * 100))}
              </span>
              <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
                wasted · {summary.totalEntries} entries
              </span>
            </div>
            <div style={{ marginTop: 8, display: "grid", gap: 4 }}>
              {Object.entries(summary.countByReason).map(([reason, count]) => (
                <div key={reason} className="reason-bar-row">
                  <span className="order-line-meta" style={{ width: 130 }}>
                    {reason.toLowerCase().replace(/_/g, " ")}
                  </span>
                  <span
                    className="reason-bar"
                    style={{ width: `${(count / maxReason) * 90}px` }}
                  />
                  <span className="order-line-meta">{count}</span>
                </div>
              ))}
            </div>
            {summary.topItems.length > 0 && (
              <div style={{ marginTop: 8 }}>
                <span className="mp-label">Most wasted</span>
                {summary.topItems.map((t) => (
                  <div key={t.itemName} className="waste-row">
                    <span>{t.itemName}</span>
                    <span className="waste-meta">
                      ×{t.entryCount} · £{t.totalCost.toFixed(2)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </>
        )
      )}
      <div style={{ marginTop: 10 }}>
        <span className="mp-label">Entries</span>
        {rows.map((entry) => (
          <div key={entry.id} className="waste-row" title={`logged ${fmtWhen(entry.createdAt)}`}>
            <span style={{ minWidth: 0 }}>
              {entry.itemName}
              {entry.quantity != null && (
                <span className="order-line-meta">
                  {" "}
                  {entry.quantity} {entry.unit ?? ""}
                </span>
              )}
              <span style={{ marginLeft: 6 }}>
                <TintChip tone="terra">
                  {entry.reason.toLowerCase().replace(/_/g, " ")}
                </TintChip>
              </span>
              {entry.notes && (
                <div className="order-line-meta" style={{ fontStyle: "italic" }}>
                  {entry.notes}
                </div>
              )}
            </span>
            <span className="waste-meta">
              {entry.costEstimate != null && `£${entry.costEstimate.toFixed(2)} · `}
              {expiryLabel(entry.occurredOn)}
            </span>
          </div>
        ))}
      </div>
      <div className="inline-note" style={{ marginTop: 8 }}>
        Entries are immutable — corrections create a new entry. Reasons feed
        the planner (expired → schedule earlier; didn't like → taste profile).
      </div>
    </div>
  );
}

/* ---- page -------------------------------------------------------------------------------------- */

type LocationFilter = StorageLocation | "ALL";

export function Pantry() {
  const items = useStore((s) => s.pantry.items);
  const budget = useStore((s) => s.pantry.budget);
  const supplierProducts = useStore((s) => s.pantry.supplierProducts);
  const fullState = useStore((s) => s);
  const [locationFilter, setLocationFilter] = useState<LocationFilter>("ALL");
  const [staplesOnly, setStaplesOnly] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [editItem, setEditItem] = useState<InventoryItemDto | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [spoilItem, setSpoilItem] = useState<InventoryItemDto | null>(null);
  const [wasteFor, setWasteFor] = useState<InventoryItemDto | null>(null);
  const [wasteOpen, setWasteOpen] = useState(false);
  const [bookOpen, setBookOpen] = useState(false);

  // GET /inventory returns ACTIVE only — other lifecycles leave the list.
  const active = items.filter((it) => it.itemStatus === "ACTIVE");
  const filtered = active.filter(
    (it) =>
      (locationFilter === "ALL" || it.storageLocation === locationFilter) &&
      (!staplesOnly || it.isStaple),
  );
  // Derived client-side — no server expiringSoon filter shipped (§9 Q3).
  const expiringSoon = active.filter(
    (it) => it.expiryDate != null && daysUntil(it.expiryDate) <= 7,
  ).length;
  const summary30 = wasteSummaryFor(fullState, "2026-05-11", MOCK_TODAY_ISO);

  const detailItem = items.find((it) => it.id === detailId);

  return (
    <div>
      <PageHeader
        title="Pantry"
        meta="Inventory · waste · equipment · budget — every override is yours and is logged"
        actions={
          <>
            <button className="btn" onClick={() => setWasteOpen(true)}>
              Log waste
            </button>
            <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
              Add item
            </button>
          </>
        }
      />

      <div style={{ marginTop: 24 }}>
        <StatStrip
          numeralSize={22}
          cells={[
            { label: "Items tracked", value: String(active.length) },
            {
              label: "Expiring soon",
              value: String(expiringSoon),
              sub: "within 7 days · derived client-side",
              warn: expiringSoon > 0,
            },
            {
              label: "Waste (30 days)",
              value: money(Math.round(summary30.totalCostEstimate * 100)),
              sub: `${summary30.totalEntries} entries`,
            },
            {
              label: "Budget target",
              value:
                budget != null && budget.enabled
                  ? `£${budget.weeklyTarget} weekly`
                  : "not set",
              sub: "spend tracking arrives in v1.5",
            },
          ]}
        />
      </div>

      <div className="nutri-tabs" role="group" aria-label="Inventory filters">
        {([{ key: "ALL", label: "All" } as const, ...LOCATIONS] as Array<{
          key: LocationFilter;
          label: string;
        }>).map((loc) => (
          <button
            key={loc.key}
            className={`filter-chip${locationFilter === loc.key ? " active" : ""}`}
            onClick={() => setLocationFilter(loc.key)}
          >
            {loc.label}
          </button>
        ))}
        <button
          className={`filter-chip${staplesOnly ? " active" : ""}`}
          onClick={() => setStaplesOnly((v) => !v)}
        >
          Staples only
        </button>
      </div>

      <div className="pantry-layout">
        <div>
          {LOCATIONS.filter(
            (loc) => locationFilter === "ALL" || loc.key === locationFilter,
          ).map((loc) => {
            const rows = filtered.filter((it) => it.storageLocation === loc.key);
            if (rows.length === 0) return null;
            return (
              <div key={loc.key} style={{ marginBottom: 22 }}>
                <div className="group-head">
                  <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
                    {loc.label}
                  </span>
                  {loc.key === "SPICE_RACK" && (
                    <span className="order-line-meta" style={{ marginLeft: 10 }}>
                      status-tracked — tap the chip to cycle
                    </span>
                  )}
                </div>
                {rows.map((item) => (
                  <PantryRow
                    key={item.id}
                    item={item}
                    onDetail={() => setDetailId(item.id)}
                    onSpoil={() => setSpoilItem(item)}
                  />
                ))}
              </div>
            );
          })}
          <div className="grocery-footnote">
            Spoiled, used-up and removed rows leave this list — the contract
            returns ACTIVE items only (no history view; flagged). Mark-spoiled
            asks the planner for a fix but does <em>not</em> log waste — that's
            a separate entry. Staple status taps ride the full item PUT (no
            focused status endpoint; flagged).
          </div>

          <details
            className="mp-card section-card"
            open={bookOpen}
            onToggle={(e) => setBookOpen((e.target as HTMLDetailsElement).open)}
          >
            <summary style={{ cursor: "pointer" }}>
              <span className="mp-label">Known products &amp; prices</span>
              <span className="order-line-meta" style={{ marginLeft: 8 }}>
                supplier catalogue cache — observed price history lives on
                Groceries
              </span>
            </summary>
            <div style={{ marginTop: 6 }}>
              {supplierProducts.map((p) => (
                <SupplierProductRow key={p.id} product={p} />
              ))}
            </div>
          </details>
        </div>

        <div style={{ display: "grid", gap: 18, alignContent: "start" }}>
          <BudgetCard />
          <WasteCard onLogWaste={() => setWasteOpen(true)} />
          <EquipmentCard />
        </div>
      </div>

      {addOpen && <ItemForm onClose={() => setAddOpen(false)} />}
      {editItem && (
        <ItemForm item={editItem} onClose={() => setEditItem(null)} />
      )}
      {spoilItem && (
        <SpoilConfirm item={spoilItem} onClose={() => setSpoilItem(null)} />
      )}
      {wasteOpen && <WasteForm onClose={() => setWasteOpen(false)} />}
      {wasteFor && (
        <WasteForm linkedItem={wasteFor} onClose={() => setWasteFor(null)} />
      )}
      {detailItem && (
        <DetailDrawer
          itemId={detailItem.id}
          onClose={() => setDetailId(null)}
          onEdit={() => {
            setEditItem(detailItem);
            setDetailId(null);
          }}
          onLogWaste={() => {
            setWasteFor(detailItem);
            setDetailId(null);
          }}
        />
      )}
    </div>
  );
}
