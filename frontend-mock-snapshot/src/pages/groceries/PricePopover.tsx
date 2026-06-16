/**
 * Tier-4 price surfaces (groceries.md §6): the per-line price popover
 * (aggregate / compare-stores / history tabs + record-a-price form) and the
 * page-level price-activity drawer over the observations feed.
 */

import { useMemo, useState } from "react";
import { Modal } from "../../components/Modal";
import { recordManualPrice, useStore } from "../../mock/store";
import type { PriceAggregateDto, PriceObservationDto } from "../../mock/types";
import {
  ConfDot,
  fmtWhen,
  money,
  PRICE_SOURCE_LABEL,
} from "./shared";

type Tab = "Aggregate" | "Stores" | "History" | "Record";

function AggregateView({ agg }: { agg: PriceAggregateDto }) {
  return (
    <div style={{ marginTop: 10 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
        <span className="mp-num" style={{ fontSize: 26 }}>
          {agg.pointEstimatePence != null ? `~${money(agg.pointEstimatePence)}` : "—"}
        </span>
        {agg.confidence != null && (
          <span
            style={{
              fontSize: 12.5,
              fontWeight: 600,
              color: agg.confidence < 0.5 ? "var(--mp-amber)" : "var(--mp-muted)",
            }}
          >
            {Math.round(agg.confidence * 100)}% confidence
          </span>
        )}
      </div>
      {agg.minPence != null && agg.maxPence != null && (
        <div className="order-line-meta" style={{ marginTop: 6 }}>
          range{" "}
          <span title={agg.minObservedAt ? `seen ${fmtWhen(agg.minObservedAt)}` : undefined}>
            {money(agg.minPence)}
          </span>
          –
          <span title={agg.maxObservedAt ? `seen ${fmtWhen(agg.maxObservedAt)}` : undefined}>
            {money(agg.maxPence)}
          </span>
        </div>
      )}
      <div className="order-line-meta" style={{ marginTop: 4 }}>
        {agg.lastSeenAt ? `last seen ${fmtWhen(agg.lastSeenAt)}` : "never observed"}
        {" · "}
        {agg.sampleCount > 0
          ? `from ${agg.sampleCount} observation${agg.sampleCount === 1 ? "" : "s"}`
          : "reference price (cold-start fallback)"}
      </div>
      {agg.isStale && (
        <div className="stale-tag" style={{ marginTop: 8 }}>
          STALE — refresh or record a price
        </div>
      )}
    </div>
  );
}

function ObservationRow({ obs }: { obs: PriceObservationDto }) {
  return (
    <div className="obs-row">
      <div style={{ flex: 1, minWidth: 0 }}>
        <span style={{ fontWeight: 600 }}>{obs.store}</span>
        <span className="order-line-meta">
          {" "}
          · {fmtWhen(obs.observedAt)}
          {obs.quantity != null && ` · ${obs.quantity} ${obs.quantityUnit ?? ""}`}
          {obs.packCount != null &&
            obs.packSizeG != null &&
            ` (${obs.packCount} × ${obs.packSizeG} g)`}
        </span>
        <div className="order-line-meta">
          <span className="tier-badge">{PRICE_SOURCE_LABEL[obs.source]}</span>{" "}
          <ConfDot value={obs.confidenceWeight} title={`weight ${obs.confidenceWeight}`} />
          {obs.groceryOrderId && ` · order ${obs.groceryOrderId}`}
          {obs.note && ` · ${obs.note}`}
        </div>
      </div>
      <span className="order-line-price">
        {obs.paidTotalPence != null
          ? money(obs.paidTotalPence)
          : obs.paidUnitPence != null
            ? `${money(obs.paidUnitPence)} unit`
            : "—"}
      </span>
    </div>
  );
}

function RecordPriceForm({
  ingredientKey,
  keyEditable,
  onSaved,
}: {
  ingredientKey: string;
  keyEditable: boolean;
  onSaved: () => void;
}) {
  const [key, setKey] = useState(ingredientKey);
  const [store, setStore] = useState("");
  const [price, setPrice] = useState("");
  const [qty, setQty] = useState("");
  const [unit, setUnit] = useState("");
  const [storeError, setStoreError] = useState(false);
  const stores = useStore((s) => [
    ...new Set(s.grocery.observations.map((o) => o.store)),
  ]);

  return (
    <div style={{ marginTop: 12, display: "grid", gap: 10 }}>
      <div>
        <label className="field-label" htmlFor="rp-key">
          Ingredient key
        </label>
        <input
          id="rp-key"
          className="text-input"
          style={{ width: "100%" }}
          value={key}
          maxLength={128}
          disabled={!keyEditable}
          onChange={(e) => setKey(e.target.value)}
        />
      </div>
      <div className="rf-grid2">
        <div>
          <label className="field-label" htmlFor="rp-store">
            Store * <span style={{ textTransform: "none" }}>(required here)</span>
          </label>
          <input
            id="rp-store"
            className="text-input"
            style={{ width: "100%", borderColor: storeError ? "var(--mp-red)" : undefined }}
            value={store}
            maxLength={64}
            list="rp-store-list"
            placeholder="e.g. tesco"
            onChange={(e) => {
              setStore(e.target.value);
              setStoreError(false);
            }}
          />
          <datalist id="rp-store-list">
            {stores.map((st) => (
              <option key={st} value={st} />
            ))}
          </datalist>
          {storeError && (
            <div style={{ color: "var(--mp-red)", fontSize: 12, marginTop: 3 }}>
              400 — store is required for a manual observation
            </div>
          )}
        </div>
        <div>
          <label className="field-label" htmlFor="rp-price">
            Price paid (£)
          </label>
          <input
            id="rp-price"
            type="number"
            min={0}
            step="0.01"
            className="text-input"
            style={{ width: "100%" }}
            value={price}
            onChange={(e) => setPrice(e.target.value)}
          />
        </div>
      </div>
      <div className="rf-grid2">
        <div>
          <label className="field-label" htmlFor="rp-qty">
            Quantity
          </label>
          <input
            id="rp-qty"
            type="number"
            min={0}
            className="text-input"
            style={{ width: "100%" }}
            value={qty}
            onChange={(e) => setQty(e.target.value)}
          />
        </div>
        <div>
          <label className="field-label" htmlFor="rp-unit">
            Unit
          </label>
          <input
            id="rp-unit"
            className="text-input"
            style={{ width: "100%" }}
            maxLength={16}
            placeholder="g / ml / items"
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
          />
        </div>
      </div>
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <button
          className="btn btn-small btn-primary"
          onClick={() => {
            if (store.trim().length === 0) {
              setStoreError(true);
              return;
            }
            recordManualPrice({
              ingredientMappingKey: key.trim(),
              store: store.trim(),
              paidTotalPence:
                price.trim() === "" ? null : Math.round(Number(price) * 100),
              quantity: qty.trim() === "" ? null : Number(qty),
              quantityUnit: unit.trim() === "" ? null : unit.trim(),
              observedAt: null,
            });
            onSaved();
          }}
        >
          Record price
        </button>
      </div>
    </div>
  );
}

export function PricePopover({
  ingredientKey,
  displayName,
  onClose,
}: {
  ingredientKey: string;
  displayName: string;
  onClose: () => void;
}) {
  const [tab, setTab] = useState<Tab>("Aggregate");
  const aggregates = useStore((s) => s.grocery.aggregates[ingredientKey]);
  const observations = useStore((s) => s.grocery.observations);

  const crossStore = aggregates?.find((a) => a.store == null) ?? aggregates?.[0];
  const storeRows = (aggregates ?? []).filter((a) => a.store != null);
  const history = useMemo(
    () => observations.filter((o) => o.ingredientMappingKey === ingredientKey),
    [observations, ingredientKey],
  );

  return (
    <Modal label={`Price history for ${displayName}`} onClose={onClose} wide>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">
          {displayName} · <span style={{ textTransform: "none" }}>{ingredientKey}</span>
        </span>
        <button className="btn btn-small" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="nutri-tabs" role="tablist" aria-label="Price views">
        {(["Aggregate", "Stores", "History", "Record"] as Tab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`filter-chip${tab === t ? " active" : ""}`}
            onClick={() => setTab(t)}
          >
            {t === "Record" ? "Record a price" : t}
          </button>
        ))}
      </div>

      {tab === "Aggregate" &&
        (crossStore ? (
          <AggregateView agg={crossStore} />
        ) : (
          // #21 404 — absence of data is a state, not an error.
          <div className="order-empty">
            No price data for this ingredient yet — record a price as you shop.
          </div>
        ))}

      {tab === "Stores" &&
        (storeRows.length > 0 ? (
          <div style={{ marginTop: 8 }}>
            {storeRows.map((agg) => (
              <div key={agg.store} className="obs-row">
                <span style={{ fontWeight: 600, width: 110 }}>{agg.store}</span>
                <span style={{ flex: 1 }}>
                  {agg.pointEstimatePence != null ? `~${money(agg.pointEstimatePence)}` : "—"}
                  <span className="order-line-meta">
                    {" "}
                    · {agg.sampleCount} obs
                    {agg.confidence != null &&
                      ` · ${Math.round(agg.confidence * 100)}%`}
                  </span>
                </span>
                {agg.isStale && <span className="stale-tag">STALE</span>}
              </div>
            ))}
          </div>
        ) : (
          <div className="order-empty">Only one store seen so far.</div>
        ))}

      {tab === "History" &&
        (history.length > 0 ? (
          <div style={{ marginTop: 8 }}>
            {history.map((obs) => (
              <ObservationRow key={obs.id} obs={obs} />
            ))}
          </div>
        ) : (
          <div className="order-empty">No observations for this ingredient yet.</div>
        ))}

      {tab === "Record" && (
        <RecordPriceForm
          ingredientKey={ingredientKey}
          keyEditable={false}
          onSaved={() => setTab("Aggregate")}
        />
      )}
    </Modal>
  );
}

/** "Price activity" drawer — the #23 audit feed + bare record form. */
export function PriceActivityDrawer({ onClose }: { onClose: () => void }) {
  const observations = useStore((s) => s.grocery.observations);
  const [recordOpen, setRecordOpen] = useState(false);
  return (
    <Modal label="Price activity" onClose={onClose} wide>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span className="mp-label">Price activity · newest first</span>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="btn btn-small" onClick={() => setRecordOpen((v) => !v)}>
            {recordOpen ? "Hide form" : "Record a price"}
          </button>
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
      {recordOpen && (
        <RecordPriceForm
          ingredientKey=""
          keyEditable
          onSaved={() => setRecordOpen(false)}
        />
      )}
      <div style={{ marginTop: 10 }}>
        {observations.map((obs) => (
          <div key={obs.id}>
            <div className="order-line-meta" style={{ marginTop: 6 }}>
              {obs.ingredientMappingKey}
            </div>
            <ObservationRow obs={obs} />
          </div>
        ))}
      </div>
    </Modal>
  );
}
