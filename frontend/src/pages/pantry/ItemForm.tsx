/**
 * Add / edit inventory item — one form, two modes (pantry.md §3c). The
 * location ↔ tracking-mode validator is enforced in the UI (tracking mode is
 * derived and read-only); the freezer panel renders iff location = FREEZER;
 * the mapping-key assist rides the nutrition lookup (s1).
 */

import { useState } from "react";
import { Modal } from "../../components/Modal";
import {
  createInventoryItem,
  updateInventoryItem,
  useStore,
} from "../../mock/store";
import { Switch } from "../nutrition/shared";
import type {
  DefrostMethod,
  InventoryItemDto,
  ItemLifecycleStatus,
  StorageLocation,
} from "../../mock/types";

const LOCATIONS: Array<{ key: StorageLocation; label: string }> = [
  { key: "FRIDGE", label: "Fridge" },
  { key: "FREEZER", label: "Freezer" },
  { key: "CUPBOARD", label: "Cupboard" },
  { key: "SPICE_RACK", label: "Spice rack" },
];

const DEFROST_METHODS: DefrostMethod[] = [
  "OVERNIGHT_FRIDGE",
  "ROOM_TEMP",
  "MICROWAVE",
  "QUICK_DEFROST",
];

const LIFECYCLES: ItemLifecycleStatus[] = [
  "ACTIVE",
  "EXHAUSTED",
  "SPOILED",
  "WASTED",
];

export function ItemForm({
  item,
  onClose,
}: {
  /** Present = edit mode (prefills + expectedVersion + lifecycle select). */
  item?: InventoryItemDto;
  onClose: () => void;
}) {
  const lookupRows = useStore((s) => s.nutrition.ingredientCache);
  const categories = useStore((s) => [
    ...new Set(s.pantry.items.map((it) => it.category)),
  ]);
  const [name, setName] = useState(item?.name ?? "");
  const [category, setCategory] = useState(item?.category ?? "");
  const [location, setLocation] = useState<StorageLocation>(
    item?.storageLocation ?? "FRIDGE",
  );
  const [qty, setQty] = useState(item?.quantity != null ? String(item.quantity) : "");
  const [unit, setUnit] = useState(item?.unit ?? "");
  const [status, setStatus] = useState(item?.status ?? "STOCKED");
  const [staple, setStaple] = useState(item?.isStaple ?? false);
  const [expiry, setExpiry] = useState(item?.expiryDate ?? "");
  const [cost, setCost] = useState(
    item?.costPaid != null ? String(item.costPaid) : "",
  );
  const [mappingKey, setMappingKey] = useState(item?.ingredientMappingKey ?? "");
  const [notes, setNotes] = useState(item?.notes ?? "");
  const [source, setSource] = useState(item?.source ?? "MANUAL_ADD");
  const [lifecycle, setLifecycle] = useState<ItemLifecycleStatus>(
    item?.itemStatus ?? "ACTIVE",
  );
  const [frozenAt, setFrozenAt] = useState(item?.freezerExtension?.frozenAt ?? "");
  const [maxWeeks, setMaxWeeks] = useState(
    item?.freezerExtension?.maxFreezeWeeks != null
      ? String(item.freezerExtension.maxFreezeWeeks)
      : "",
  );
  const [defrost, setDefrost] = useState<DefrostMethod | "">(
    item?.freezerExtension?.defrostMethod ?? "",
  );
  const [leadHours, setLeadHours] = useState(
    item?.freezerExtension?.defrostLeadTimeHours != null
      ? String(item.freezerExtension.defrostLeadTimeHours)
      : "",
  );
  const [error, setError] = useState<string | null>(null);

  const trackingMode = location === "SPICE_RACK" ? "STATUS" : "QUANTITY";

  const save = () => {
    if (name.trim() === "" || category.trim() === "") {
      setError("Name and category are required");
      return;
    }
    const freezerExtension =
      location === "FREEZER" &&
      (frozenAt !== "" || maxWeeks !== "" || defrost !== "" || leadHours !== "")
        ? {
            frozenAt: frozenAt === "" ? null : frozenAt,
            maxFreezeWeeks: maxWeeks === "" ? null : Number(maxWeeks),
            defrostMethod: defrost === "" ? null : defrost,
            defrostLeadTimeHours: leadHours === "" ? null : Number(leadHours),
            sourceRecipeId: item?.freezerExtension?.sourceRecipeId ?? null,
          }
        : null;
    const base = {
      name: name.trim(),
      category: category.trim(),
      storageLocation: location,
      trackingMode,
      quantity: trackingMode === "QUANTITY" && qty !== "" ? Number(qty) : null,
      unit: trackingMode === "QUANTITY" && unit !== "" ? unit : null,
      costPaid: cost === "" ? null : Number(cost),
      status: trackingMode === "STATUS" ? status : null,
      isStaple: staple,
      expiryDate: expiry === "" ? null : expiry,
      ingredientMappingKey: mappingKey.trim() === "" ? null : mappingKey.trim(),
      notes: notes.trim() === "" ? null : notes.trim(),
      source,
      freezerExtension,
    } as const;
    const ok = item
      ? updateInventoryItem(item.id, {
          ...base,
          sourceRef: item.sourceRef ?? null, // preserved, never user-entered
          itemStatus: lifecycle,
          expectedVersion: item.version,
        })
      : createInventoryItem({ ...base, sourceRef: null });
    if (ok) onClose();
  };

  return (
    <Modal label={item ? `Edit ${item.name}` : "Add pantry item"} onClose={onClose} wide>
      <span className="mp-label">{item ? `Edit · ${item.name}` : "Add item"}</span>
      <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="if-name">
              Name *
            </label>
            <input
              id="if-name"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={128}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div>
            <label className="field-label" htmlFor="if-category">
              Category *
            </label>
            <input
              id="if-category"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={64}
              list="if-cat-list"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
            />
            <datalist id="if-cat-list">
              {categories.map((c) => (
                <option key={c} value={c} />
              ))}
            </datalist>
          </div>
        </div>

        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="if-location">
              Location *
            </label>
            <select
              id="if-location"
              className="time-select"
              style={{ width: "100%" }}
              value={location}
              onChange={(e) => setLocation(e.target.value as StorageLocation)}
            >
              {LOCATIONS.map((l) => (
                <option key={l.key} value={l.key}>
                  {l.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <span className="field-label">Tracking mode (derived)</span>
            <div style={{ paddingTop: 8, fontSize: 13.5 }}>
              {trackingMode === "STATUS"
                ? "Status — spice-rack items track stocked / low / out"
                : "Quantity — fridge, freezer and cupboard track amounts"}
            </div>
          </div>
        </div>

        {trackingMode === "QUANTITY" ? (
          <div className="rf-grid2">
            <div>
              <label className="field-label" htmlFor="if-qty">
                Quantity
              </label>
              <input
                id="if-qty"
                type="number"
                min={0}
                max={1000000}
                className="text-input"
                style={{ width: "100%" }}
                value={qty}
                onChange={(e) => setQty(e.target.value)}
              />
            </div>
            <div>
              <label className="field-label" htmlFor="if-unit">
                Unit
              </label>
              <input
                id="if-unit"
                className="text-input"
                style={{ width: "100%" }}
                maxLength={16}
                placeholder="g / ml / items / portions"
                value={unit}
                onChange={(e) => setUnit(e.target.value)}
              />
            </div>
          </div>
        ) : (
          <div>
            <label className="field-label" htmlFor="if-status">
              Status
            </label>
            <select
              id="if-status"
              className="time-select"
              value={status ?? "STOCKED"}
              onChange={(e) => setStatus(e.target.value as "STOCKED" | "LOW" | "OUT")}
            >
              <option value="STOCKED">Stocked</option>
              <option value="LOW">Low</option>
              <option value="OUT">Out</option>
            </select>
          </div>
        )}

        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="if-expiry">
              Expiry date
            </label>
            <input
              id="if-expiry"
              type="date"
              className="text-input"
              style={{ width: "100%" }}
              value={expiry}
              onChange={(e) => setExpiry(e.target.value)}
            />
            <div className="inline-note" style={{ marginTop: 3 }}>
              your date wins over our estimate
            </div>
          </div>
          <div>
            <label className="field-label" htmlFor="if-cost">
              Cost paid (£)
            </label>
            <input
              id="if-cost"
              type="number"
              min={0}
              step="0.01"
              className="text-input"
              style={{ width: "100%" }}
              value={cost}
              onChange={(e) => setCost(e.target.value)}
            />
          </div>
        </div>

        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="if-key">
              Ingredient mapping key
            </label>
            <input
              id="if-key"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={128}
              list="if-key-list"
              value={mappingKey}
              onChange={(e) => setMappingKey(e.target.value)}
            />
            <datalist id="if-key-list">
              {lookupRows.map((r) => (
                <option key={r.searchTerm} value={r.searchTerm.replace(/\s+/g, ".")} />
              ))}
            </datalist>
            <div className="inline-note" style={{ marginTop: 3 }}>
              links this item to recipes and nutrition — leave empty if unsure
            </div>
          </div>
          <div>
            <label className="field-label" htmlFor="if-source">
              Source
            </label>
            <select
              id="if-source"
              className="time-select"
              style={{ width: "100%" }}
              value={source}
              disabled={item != null && (source === "TESCO_ORDER" || source === "BATCH_COOK")}
              onChange={(e) =>
                setSource(e.target.value as "MANUAL_ADD" | "OTHER_SHOP" | "GIFT")
              }
            >
              {source === "TESCO_ORDER" && <option value="TESCO_ORDER">Tesco order (system)</option>}
              {source === "BATCH_COOK" && <option value="BATCH_COOK">Batch cook (system)</option>}
              <option value="MANUAL_ADD">Manual add</option>
              <option value="OTHER_SHOP">Other shop</option>
              <option value="GIFT">Gift</option>
            </select>
          </div>
        </div>

        <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
          <Switch on={staple} onToggle={() => setStaple((v) => !v)} label="Staple" />
          <span style={{ fontSize: 13.5 }}>
            Staple — auto-replenished when low or out
          </span>
        </div>

        {location === "FREEZER" && (
          <div className="mp-card" style={{ padding: "12px 14px" }}>
            <span className="mp-label">Freezer details</span>
            <div className="rf-grid2" style={{ marginTop: 8 }}>
              <div>
                <label className="field-label" htmlFor="if-frozen">
                  Frozen on
                </label>
                <input
                  id="if-frozen"
                  type="date"
                  className="text-input"
                  style={{ width: "100%" }}
                  value={frozenAt}
                  onChange={(e) => setFrozenAt(e.target.value)}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="if-weeks">
                  Keeps (weeks)
                </label>
                <input
                  id="if-weeks"
                  type="number"
                  min={0}
                  className="text-input"
                  style={{ width: "100%" }}
                  value={maxWeeks}
                  onChange={(e) => setMaxWeeks(e.target.value)}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="if-defrost">
                  Defrost method
                </label>
                <select
                  id="if-defrost"
                  className="time-select"
                  style={{ width: "100%" }}
                  value={defrost}
                  onChange={(e) => setDefrost(e.target.value as DefrostMethod | "")}
                >
                  <option value="">—</option>
                  {DEFROST_METHODS.map((m) => (
                    <option key={m} value={m}>
                      {m.toLowerCase().replace(/_/g, " ")}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label" htmlFor="if-lead">
                  Defrost lead time (h)
                </label>
                <input
                  id="if-lead"
                  type="number"
                  min={0}
                  className="text-input"
                  style={{ width: "100%" }}
                  value={leadHours}
                  onChange={(e) => setLeadHours(e.target.value)}
                />
              </div>
            </div>
            {item?.freezerExtension?.sourceRecipeId && (
              <div className="inline-note" style={{ marginTop: 6 }}>
                batch-cooked from recipe {item.freezerExtension.sourceRecipeId}{" "}
                (system-set)
              </div>
            )}
          </div>
        )}

        {item && (
          <div className="rf-grid2">
            <div>
              <label className="field-label" htmlFor="if-lifecycle">
                Lifecycle (repair path — mark-spoiled has no undo)
              </label>
              <select
                id="if-lifecycle"
                className="time-select"
                style={{ width: "100%" }}
                value={lifecycle}
                onChange={(e) => setLifecycle(e.target.value as ItemLifecycleStatus)}
              >
                {LIFECYCLES.map((st) => (
                  <option key={st} value={st}>
                    {st.toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            <div />
          </div>
        )}

        <div>
          <label className="field-label" htmlFor="if-notes">
            Notes
          </label>
          <input
            id="if-notes"
            className="text-input"
            style={{ width: "100%" }}
            maxLength={255}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </div>

        {error && (
          <div style={{ color: "var(--mp-red)", fontSize: 12.5 }}>{error}</div>
        )}

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button className="btn btn-small" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-small btn-primary" onClick={save}>
            {item ? "Save changes" : "Add item"}
          </button>
        </div>
      </div>
    </Modal>
  );
}
