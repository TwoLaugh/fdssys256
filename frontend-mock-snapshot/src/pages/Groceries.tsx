/**
 * Groceries — the grocery module's whole user surface, rebuilt on the
 * production contract (design/frontend/pages/groceries.md). All four tiers:
 * shopping list (1), mark-bought capture (2), provider orders (3, side
 * panel — never the spine), price history (4).
 */

import { useMemo, useState } from "react";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import { TintChip } from "../components/TintChip";
import {
  asBoughtUnit,
  buildListExport,
  bulkMarkBought,
  currentShoppingList,
  markBoughtLine,
  markBoughtOneTap,
  recalculateShoppingList,
  refreshPrices,
  undoMarkBought,
  useStore,
} from "../mock/store";
import type {
  BoughtUnit,
  ExportFormat,
  ShoppingListDto,
  ShoppingListLineDto,
} from "../mock/types";
import { OrdersPanel } from "./groceries/OrdersPanel";
import { PriceActivityDrawer, PricePopover } from "./groceries/PricePopover";
import {
  approxMoney,
  ConfDot,
  confidenceLabel,
  downloadFile,
  FULFILMENT_MARK,
  fmtWhen,
  money,
  openPrintWindow,
  packSuggestion,
  VIA_LABEL,
} from "./groceries/shared";

const BOUGHT_UNITS: BoughtUnit[] = [
  "g", "kg", "ml", "l", "items", "pt", "tsp", "tbsp", "cup",
];

/* ---- mark-bought popover (§4a — every MarkBoughtRequest field) -------------------- */

function MarkBoughtPopover({
  list,
  line,
  onClose,
}: {
  list: ShoppingListDto;
  line: ShoppingListLineDto;
  onClose: () => void;
}) {
  const packQty =
    line.suggestedPackCount != null && line.suggestedPackSizeG != null
      ? line.suggestedPackCount * line.suggestedPackSizeG
      : null;
  const [qty, setQty] = useState(String(packQty ?? line.requestedQuantity));
  const [unit, setUnit] = useState<BoughtUnit>(
    packQty != null ? "g" : asBoughtUnit(line.requestedUnit),
  );
  const [price, setPrice] = useState("");
  const [store, setStore] = useState("");
  const [when, setWhen] = useState("");
  const stores = useStore((s) => [
    ...new Set(s.grocery.observations.map((o) => o.store)),
  ]);

  return (
    <Modal label={`Mark ${line.displayName} bought`} onClose={onClose}>
      <span className="mp-label">Mark bought · {line.displayName}</span>
      <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="mb-qty">
              Quantity *
            </label>
            <input
              id="mb-qty"
              type="number"
              min={0.001}
              max={1000000}
              className="text-input"
              style={{ width: "100%" }}
              value={qty}
              onChange={(e) => setQty(e.target.value)}
            />
          </div>
          <div>
            <label className="field-label" htmlFor="mb-unit">
              Unit *
            </label>
            <select
              id="mb-unit"
              className="time-select"
              style={{ width: "100%" }}
              value={unit}
              onChange={(e) => setUnit(e.target.value as BoughtUnit)}
            >
              {BOUGHT_UNITS.map((u) => (
                <option key={u} value={u}>
                  {u}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="mb-price">
              Price paid (£)
            </label>
            <input
              id="mb-price"
              type="number"
              min={0}
              step="0.01"
              className="text-input"
              style={{ width: "100%" }}
              placeholder={
                line.estimatedLinePence != null
                  ? `est. ${(line.estimatedLinePence / 100).toFixed(2)}`
                  : undefined
              }
              value={price}
              onChange={(e) => setPrice(e.target.value)}
            />
            <div className="inline-note" style={{ marginTop: 3 }}>
              optional — feeds your price history
            </div>
          </div>
          <div>
            <label className="field-label" htmlFor="mb-store">
              Store
            </label>
            <input
              id="mb-store"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={64}
              list="mb-store-list"
              value={store}
              onChange={(e) => setStore(e.target.value)}
            />
            <datalist id="mb-store-list">
              {stores.map((st) => (
                <option key={st} value={st} />
              ))}
            </datalist>
            <div className="inline-note" style={{ marginTop: 3 }}>
              optional — enables cross-store comparison
            </div>
          </div>
        </div>
        <div>
          <label className="field-label" htmlFor="mb-when">
            When (default now)
          </label>
          <input
            id="mb-when"
            type="datetime-local"
            className="text-input"
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </div>
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 16, justifyContent: "flex-end" }}>
        <button className="btn btn-small" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-small btn-primary"
          onClick={() => {
            const q = Number(qty);
            if (!Number.isFinite(q) || q <= 0) return;
            markBoughtLine(list.id, line.id, {
              boughtQuantity: q,
              boughtUnit: unit,
              boughtPricePence:
                price.trim() === "" ? null : Math.round(Number(price) * 100),
              store: store.trim() === "" ? null : store.trim(),
              boughtAt: when === "" ? null : `${when}:00Z`,
            });
            onClose();
          }}
        >
          Mark bought
        </button>
      </div>
    </Modal>
  );
}

/* ---- one list line (§3b field map) ----------------------------------------------- */

function LineRow({
  list,
  line,
  selectMode,
  selected,
  onToggleSelect,
  onOpenDetail,
  onOpenPrice,
  retro,
}: {
  list: ShoppingListDto;
  line: ShoppingListLineDto;
  selectMode: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onOpenDetail: () => void;
  onOpenPrice: () => void;
  /** History-drawer variant: retro mark-bought stays enabled, actions trim. */
  retro?: boolean;
}) {
  const mark = FULFILMENT_MARK[line.fulfilmentStatus];
  const undoable =
    line.fulfilmentStatus === "BOUGHT" &&
    (line.boughtVia === "MANUAL" || line.boughtVia === "BULK_TOTAL");
  return (
    <div className="grocery-row">
      {selectMode ? (
        <button
          type="button"
          role="checkbox"
          aria-checked={selected}
          aria-label={`Select ${line.displayName}`}
          className={`bought-box${selected ? " bought" : ""}`}
          disabled={line.fulfilmentStatus !== "UNFILLED"}
          onClick={onToggleSelect}
        >
          {selected ? "✓" : ""}
        </button>
      ) : (
        <button
          type="button"
          role="checkbox"
          aria-checked={line.fulfilmentStatus === "BOUGHT"}
          aria-label={`${line.displayName} ${mark.label}`}
          className={`bought-box${line.fulfilmentStatus === "BOUGHT" ? " bought" : ""}`}
          style={
            line.fulfilmentStatus !== "BOUGHT" && line.fulfilmentStatus !== "UNFILLED"
              ? { color: mark.color, borderColor: "transparent", fontSize: 13 }
              : undefined
          }
          disabled={line.fulfilmentStatus !== "UNFILLED" && line.fulfilmentStatus !== "BOUGHT"}
          onClick={() => {
            if (line.fulfilmentStatus === "UNFILLED") {
              markBoughtOneTap(list.id, line.id);
            }
          }}
          title={
            line.fulfilmentStatus === "UNFILLED"
              ? "One tap = suggested pack, no price recorded"
              : mark.label
          }
        >
          {line.fulfilmentStatus === "BOUGHT" ? "✓" : mark.glyph === "○" ? "" : mark.glyph}
        </button>
      )}

      <div style={{ flex: 1, minWidth: 0 }}>
        <span className={`grocery-name${mark.struck ? " bought" : ""}`}>
          {line.displayName}
        </span>
        {line.qualityNotes && (
          <span style={{ marginLeft: 8 }}>
            <TintChip tone="terra">{line.qualityNotes}</TintChip>
          </span>
        )}
        {line.fulfilmentStatus === "SUBSTITUTED" && (
          <span style={{ marginLeft: 8 }}>
            <TintChip>⇄ substituted</TintChip>
          </span>
        )}
        {line.fulfilmentStatus === "DROPPED" && (
          <span style={{ marginLeft: 8 }} className="order-line-meta">
            dropped
          </span>
        )}
        {packSuggestion(line) && line.fulfilmentStatus === "UNFILLED" && (
          <div className="order-line-meta">{packSuggestion(line)}</div>
        )}
        {(line.fulfilmentStatus === "BOUGHT" ||
          line.fulfilmentStatus === "SUBSTITUTED") &&
          line.boughtAt && (
            <div className="order-line-meta">
              ✓ {line.boughtQuantity} {line.boughtUnit}
              {line.boughtPricePence != null && ` · ${money(line.boughtPricePence)}`}
              {" · "}
              {fmtWhen(line.boughtAt)}
              {line.boughtVia && (
                <span className="tier-badge" style={{ marginLeft: 6 }}>
                  {VIA_LABEL[line.boughtVia]}
                </span>
              )}
            </div>
          )}
      </div>

      <span className="grocery-qty">
        {line.requestedQuantity} {line.requestedUnit}
      </span>

      <button
        className="grocery-price link-btn"
        onClick={onOpenPrice}
        title={
          line.estimatedUnitPence != null
            ? `${line.estimatedUnitPence}p unit · price history`
            : "price history"
        }
      >
        {line.estimatedLinePence != null ? approxMoney(line.estimatedLinePence) : "—"}
        {line.estimatedConfidence != null && line.estimatedConfidence < 0.5 && (
          <span style={{ marginLeft: 5 }}>
            <ConfDot value={line.estimatedConfidence} />
          </span>
        )}
      </button>

      <span className="grocery-stale">
        {line.isStaleEstimate && <span className="stale-tag">STALE</span>}
      </span>

      {!selectMode && (
        <span className="grocery-actions">
          {line.fulfilmentStatus === "UNFILLED" && (
            <button className="btn btn-small" onClick={onOpenDetail}>
              Mark…
            </button>
          )}
          {undoable && !retro && (
            <button
              className="btn btn-small"
              onClick={() => undoMarkBought(list.id, line.id)}
              title="Removes the mark and writes a compensating price note — the pantry item is not removed automatically"
            >
              Undo
            </button>
          )}
        </span>
      )}
    </div>
  );
}

/* ---- list body (current + history views share it) --------------------------------- */

function ListLines({
  list,
  selectMode,
  selectedIds,
  onToggleSelect,
  onOpenDetail,
  onOpenPrice,
  retro,
}: {
  list: ShoppingListDto;
  selectMode: boolean;
  selectedIds: Set<string>;
  onToggleSelect: (id: string) => void;
  onOpenDetail: (id: string) => void;
  onOpenPrice: (line: ShoppingListLineDto) => void;
  retro?: boolean;
}) {
  const planned = list.lines.filter((ln) => ln.lineType === "PLANNED_DEMAND");
  const staples = list.lines.filter(
    (ln) => ln.lineType === "STAPLE_REPLENISHMENT",
  );
  const section = (
    label: string,
    caption: string | null,
    lines: ShoppingListLineDto[],
  ) =>
    lines.length > 0 && (
      <div style={{ marginBottom: 22 }}>
        <div className="group-head">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            {label}
          </span>
          {caption && (
            <span className="order-line-meta" style={{ marginLeft: 10 }}>
              {caption}
            </span>
          )}
        </div>
        {lines.map((ln) => (
          <LineRow
            key={ln.id}
            list={list}
            line={ln}
            selectMode={selectMode}
            selected={selectedIds.has(ln.id)}
            onToggleSelect={() => onToggleSelect(ln.id)}
            onOpenDetail={() => onOpenDetail(ln.id)}
            onOpenPrice={() => onOpenPrice(ln)}
            retro={retro}
          />
        ))}
      </div>
    );
  return (
    <>
      {section("Planned demand", null, planned)}
      {section(
        "Staples to replenish",
        "added because it ran low — not from a recipe",
        staples,
      )}
    </>
  );
}

/* ---- history drawer (#3 + retro marking via #2) ------------------------------------ */

function HistoryDrawer({ onClose }: { onClose: () => void }) {
  const lists = useStore((s) => s.grocery.lists);
  const [openId, setOpenId] = useState<string | null>(null);
  const [detailLineId, setDetailLineId] = useState<string | null>(null);
  const [priceLine, setPriceLine] = useState<ShoppingListLineDto | null>(null);
  const open = lists.find((l) => l.id === openId);
  const detailLine = open?.lines.find((ln) => ln.id === detailLineId);

  return (
    <Modal label="Shopping list history" onClose={onClose} wide>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">List history · newest first</span>
        <button className="btn btn-small" onClick={onClose}>
          Close
        </button>
      </div>
      <div style={{ marginTop: 10 }}>
        {lists.map((l) => {
          const bought = l.lines.filter(
            (ln) =>
              ln.fulfilmentStatus === "BOUGHT" ||
              ln.fulfilmentStatus === "SUBSTITUTED",
          ).length;
          return (
            <div key={l.id}>
              <button
                className="history-row-btn"
                onClick={() => setOpenId(openId === l.id ? null : l.id)}
                aria-expanded={openId === l.id}
              >
                <span style={{ fontWeight: 600 }}>{fmtWhen(l.generatedAt)}</span>
                <span className="order-line-meta">
                  generation {l.planGeneration} ·{" "}
                  {l.estimatedTotalPence != null
                    ? approxMoney(l.estimatedTotalPence)
                    : "no estimate"}{" "}
                  · {bought} of {l.lines.length} bought
                </span>
                {l.supersededAt != null && (
                  <span className="mp-chip muted">superseded</span>
                )}
              </button>
              {openId === l.id && (
                <div style={{ padding: "4px 0 10px" }}>
                  <div className="inline-note" style={{ marginBottom: 8 }}>
                    Retroactive mark-bought stays enabled on history — useful
                    when you shopped from an old list.
                  </div>
                  <ListLines
                    list={l}
                    selectMode={false}
                    selectedIds={new Set()}
                    onToggleSelect={() => undefined}
                    onOpenDetail={(id) => setDetailLineId(id)}
                    onOpenPrice={(ln) => setPriceLine(ln)}
                    retro
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>
      {open && detailLine && (
        <MarkBoughtPopover
          list={open}
          line={detailLine}
          onClose={() => setDetailLineId(null)}
        />
      )}
      {priceLine && (
        <PricePopover
          ingredientKey={priceLine.ingredientMappingKey}
          displayName={priceLine.displayName}
          onClose={() => setPriceLine(null)}
        />
      )}
    </Modal>
  );
}

/* ---- export menu (#5 per-format behaviours) ----------------------------------------- */

const EXPORT_ENTRIES: Array<{
  label: string;
  format: ExportFormat;
  run: (content: string) => void;
}> = [
  {
    label: "Print / PDF",
    format: "PRINTABLE_HTML",
    run: (content) => openPrintWindow(content),
  },
  {
    label: "Copy to clipboard",
    format: "PLAIN_TEXT",
    run: (content) => {
      void navigator.clipboard?.writeText(content);
    },
  },
  {
    label: "Markdown",
    format: "MARKDOWN",
    run: (content) => downloadFile("shopping-list.md", content, "text/markdown"),
  },
  {
    label: "CSV",
    format: "CSV",
    run: (content) => downloadFile("shopping-list.csv", content, "text/csv"),
  },
  {
    label: "Email / share",
    format: "PLAIN_TEXT",
    run: (content) => {
      window.location.href = `mailto:?subject=Shopping%20list&body=${encodeURIComponent(content)}`;
    },
  },
];

/* ---- page ----------------------------------------------------------------------------- */

export function Groceries() {
  const list = useStore(currentShoppingList);
  const budget = useStore((s) => s.pantry.budget);
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkSpend, setBulkSpend] = useState("");
  const [bulkStore, setBulkStore] = useState("");
  const [detailLineId, setDetailLineId] = useState<string | null>(null);
  const [priceLine, setPriceLine] = useState<ShoppingListLineDto | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [activityOpen, setActivityOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);

  const boughtCount = useMemo(
    () =>
      (list?.lines ?? []).filter(
        (ln) =>
          ln.fulfilmentStatus === "BOUGHT" ||
          ln.fulfilmentStatus === "SUBSTITUTED",
      ).length,
    [list],
  );

  const detailLine = list?.lines.find((ln) => ln.id === detailLineId);
  const coldStart = list?.costConfidence == null || list.costConfidence < 0.3;

  const toggleSelect = (id: string) =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div>
      <PageHeader
        title="Groceries"
        meta={
          list
            ? `For plan week 8–14 June · generation ${list.planGeneration} · calculated ${fmtWhen(list.generatedAt)}`
            : "No shopping list for this week"
        }
        actions={
          <>
            <span className="suggest-anchor">
              <button
                className="btn"
                disabled={!list}
                aria-expanded={exportOpen}
                onClick={() => setExportOpen((v) => !v)}
              >
                Export ▾
              </button>
              {exportOpen && list && (
                <span className="mp-card suggest-pop" style={{ minWidth: 190 }}>
                  {EXPORT_ENTRIES.map((entry) => (
                    <button
                      key={entry.label}
                      className="suggest-row"
                      onClick={() => {
                        entry.run(buildListExport(list, entry.format));
                        setExportOpen(false);
                      }}
                    >
                      <span>{entry.label}</span>
                      <span className="order-line-meta">{entry.format.toLowerCase()}</span>
                    </button>
                  ))}
                </span>
              )}
            </span>
            <button
              className="btn"
              onClick={recalculateShoppingList}
              title="Re-derives the list from the plan and your pantry — bought marks on this generation are kept"
            >
              Recalculate
            </button>
            <button className="btn" onClick={() => setHistoryOpen(true)}>
              History
            </button>
            <button
              className={`btn${selectMode ? " btn-primary" : ""}`}
              disabled={!list}
              onClick={() => {
                setSelectMode((v) => !v);
                setSelectedIds(new Set());
              }}
            >
              {selectMode ? "Done selecting" : "Select"}
            </button>
          </>
        }
      />

      {!list ? (
        // #1 404 — empty state, not an error.
        <div className="mp-card section-card">
          <span className="mp-serif" style={{ fontSize: 20 }}>
            No shopping list for this week.
          </span>
          <div style={{ marginTop: 12 }}>
            <button className="btn btn-primary" onClick={recalculateShoppingList}>
              Recalculate from the plan
            </button>
          </div>
        </div>
      ) : (
        <>
          <div style={{ marginTop: 24 }}>
            <StatStrip
              numeralSize={22}
              cells={[
                {
                  label: "Projected total",
                  value:
                    list.estimatedTotalPence != null
                      ? money(list.estimatedTotalPence)
                      : "no price data yet",
                  sub: confidenceLabel(list.costConfidence) ?? undefined,
                  warn: list.costConfidence != null && list.costConfidence < 0.5,
                },
                {
                  label: "Items bought",
                  value: `${boughtCount} of ${list.lines.length}`,
                  sub: "incl. substituted",
                },
                {
                  label: "Stale prices",
                  value: String(list.staleIngredientCount),
                  sub: "last priced > 3 months ago",
                  warn: list.staleIngredientCount > 0,
                },
                {
                  label: "Budget target",
                  value:
                    budget != null && budget.enabled
                      ? `£${budget.weeklyTarget} weekly`
                      : "not set",
                  sub: "managed in Pantry",
                },
              ]}
            />
          </div>

          <div className="grocery-caption-row">
            <button
              className="btn btn-small"
              onClick={refreshPrices}
              title="Quotes run without intent to place — cheap, explicit, feeds the price cache"
            >
              Refresh prices
            </button>
            {!list.pantryTrackingEnabled && (
              <span className="order-line-meta" style={{ color: "var(--mp-amber)" }}>
                pantry stock not subtracted — tracking is off ·{" "}
                <a href="/settings">Settings</a>
              </span>
            )}
            {list.notes && <span className="order-line-meta">{list.notes}</span>}
            {coldStart && (
              <span className="mp-serif" style={{ fontSize: 15 }}>
                Enter prices as you shop — estimates improve after a few weeks.
              </span>
            )}
          </div>

          <div className="grocery-layout">
            <div>
              <ListLines
                list={list}
                selectMode={selectMode}
                selectedIds={selectedIds}
                onToggleSelect={toggleSelect}
                onOpenDetail={(id) => setDetailLineId(id)}
                onOpenPrice={(ln) => setPriceLine(ln)}
              />
              <div className="grocery-footnote">
                Estimates are approximations (~) with confidence — the contract
                carries no ± variance band (flagged as a backend gap). STALE =
                freshest observation older than 3 months. Recalculate is
                idempotent within a plan generation — pantry drift needs a new
                generation. ·{" "}
                <button className="link-btn" onClick={() => setActivityOpen(true)}>
                  Price activity
                </button>
              </div>
            </div>

            <OrdersPanel />
          </div>

          {selectMode && (
            <div className="select-bar mp-card">
              <span style={{ fontWeight: 600, fontSize: 13.5 }}>
                {selectedIds.size} selected
              </span>
              <input
                className="text-input"
                style={{ width: 130 }}
                type="number"
                min={0}
                step="0.01"
                placeholder="Total spend £"
                aria-label="Total spend in pounds"
                value={bulkSpend}
                onChange={(e) => setBulkSpend(e.target.value)}
                title="Optional — distributed proportionally to estimated line costs; unpriced lines share uniformly"
              />
              <input
                className="text-input"
                style={{ width: 120 }}
                maxLength={64}
                placeholder="Store"
                aria-label="Store for the batch"
                value={bulkStore}
                onChange={(e) => setBulkStore(e.target.value)}
              />
              <button
                className="btn btn-small btn-primary"
                disabled={selectedIds.size === 0}
                onClick={() => {
                  bulkMarkBought(
                    list.id,
                    [...selectedIds],
                    bulkSpend.trim() === ""
                      ? null
                      : Math.round(Number(bulkSpend) * 100),
                    bulkStore.trim() === "" ? null : bulkStore.trim(),
                  );
                  setSelectMode(false);
                  setSelectedIds(new Set());
                  setBulkSpend("");
                  setBulkStore("");
                }}
              >
                Mark all bought
              </button>
              <span className="order-line-meta">
                total spend is split across estimates as lower-confidence
                observations
              </span>
            </div>
          )}
        </>
      )}

      {list && detailLine && (
        <MarkBoughtPopover
          list={list}
          line={detailLine}
          onClose={() => setDetailLineId(null)}
        />
      )}
      {priceLine && (
        <PricePopover
          ingredientKey={priceLine.ingredientMappingKey}
          displayName={priceLine.displayName}
          onClose={() => setPriceLine(null)}
        />
      )}
      {historyOpen && <HistoryDrawer onClose={() => setHistoryOpen(false)} />}
      {activityOpen && (
        <PriceActivityDrawer onClose={() => setActivityOpen(false)} />
      )}
    </div>
  );
}
