/**
 * Log-waste form (pantry.md §4a) — LogWasteRequest field-complete. Entries
 * are immutable; linked entries deduct from the inventory row (422 when the
 * quantity exceeds the tracked remainder — validated inline here too).
 */

import { useState } from "react";
import { Modal } from "../../components/Modal";
import { MOCK_TODAY_ISO } from "../../mock/seed";
import { logWaste, useStore } from "../../mock/store";
import type { InventoryItemDto, WasteReason } from "../../mock/types";

const REASONS: Array<{ key: WasteReason; label: string; caption?: string }> = [
  { key: "EXPIRED", label: "Expired", caption: "didn't use in time" },
  { key: "LEFTOVER_NOT_EATEN", label: "Leftover not eaten" },
  {
    key: "DIDNT_LIKE",
    label: "Didn't like",
    caption: "this one also feeds your taste preferences",
  },
  { key: "SPOILED_EARLY", label: "Spoiled early", caption: "went off before the date" },
  { key: "MADE_TOO_MUCH", label: "Made too much" },
];

export function WasteForm({
  linkedItem,
  onClose,
}: {
  /** Set when opened from an item row — locks the name + enables deduction. */
  linkedItem?: InventoryItemDto;
  onClose: () => void;
}) {
  const activeItems = useStore((s) =>
    s.pantry.items.filter((it) => it.itemStatus === "ACTIVE"),
  );
  const [linkedId, setLinkedId] = useState(linkedItem?.id ?? "");
  const linked =
    linkedItem ?? activeItems.find((it) => it.id === linkedId);
  const [name, setName] = useState(linkedItem?.name ?? "");
  const [qty, setQty] = useState("");
  const [unit, setUnit] = useState(linkedItem?.unit ?? "");
  const [reason, setReason] = useState<WasteReason>("EXPIRED");
  const [cost, setCost] = useState(
    linkedItem?.costPaid != null ? String(linkedItem.costPaid) : "",
  );
  const [date, setDate] = useState(MOCK_TODAY_ISO);
  const [notes, setNotes] = useState("");
  const [qtyError, setQtyError] = useState<string | null>(null);

  const itemName = linked ? linked.name : name;
  const reasonMeta = REASONS.find((r) => r.key === reason);

  const save = () => {
    if (itemName.trim() === "") return;
    if (
      linked &&
      linked.trackingMode === "QUANTITY" &&
      qty !== "" &&
      (unit === (linked.unit ?? "")) &&
      Number(qty) > (linked.quantity ?? 0)
    ) {
      setQtyError(
        `422 — that's more than you have tracked (${linked.quantity ?? 0} ${linked.unit ?? ""} left)`,
      );
      return;
    }
    const ok = logWaste({
      inventoryItemId: linked?.id ?? null,
      itemName: itemName.trim(),
      quantity: qty === "" ? null : Number(qty),
      unit: unit === "" ? null : unit,
      reason,
      costEstimate: cost === "" ? null : Number(cost),
      occurredOn: date,
      notes: notes.trim() === "" ? null : notes.trim(),
    });
    if (ok) onClose();
  };

  return (
    <Modal label="Log waste" onClose={onClose}>
      <span className="mp-label">Log waste</span>
      <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
        {!linkedItem && (
          <div>
            <label className="field-label" htmlFor="wf-linked">
              Linked pantry item (optional)
            </label>
            <select
              id="wf-linked"
              className="time-select"
              style={{ width: "100%" }}
              value={linkedId}
              onChange={(e) => {
                setLinkedId(e.target.value);
                const it = activeItems.find((x) => x.id === e.target.value);
                if (it) {
                  setUnit(it.unit ?? "");
                  if (it.costPaid != null) setCost(String(it.costPaid));
                }
              }}
            >
              <option value="">— not tracked —</option>
              {activeItems.map((it) => (
                <option key={it.id} value={it.id}>
                  {it.name}
                </option>
              ))}
            </select>
            <div className="inline-note" style={{ marginTop: 3 }}>
              linking deducts the quantity from the row
            </div>
          </div>
        )}
        {!linked && (
          <div>
            <label className="field-label" htmlFor="wf-name">
              Item name *
            </label>
            <input
              id="wf-name"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={128}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="waste needn't be tracked inventory"
            />
          </div>
        )}
        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="wf-qty">
              Quantity
            </label>
            <input
              id="wf-qty"
              type="number"
              min={0}
              className="text-input"
              style={{
                width: "100%",
                borderColor: qtyError ? "var(--mp-red)" : undefined,
              }}
              value={qty}
              onChange={(e) => {
                setQty(e.target.value);
                setQtyError(null);
              }}
            />
            {qtyError && (
              <div style={{ color: "var(--mp-red)", fontSize: 12, marginTop: 3 }}>
                {qtyError}
              </div>
            )}
          </div>
          <div>
            <label className="field-label" htmlFor="wf-unit">
              Unit
            </label>
            <input
              id="wf-unit"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={16}
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
            />
          </div>
        </div>
        <div>
          <label className="field-label" htmlFor="wf-reason">
            Reason *
          </label>
          <select
            id="wf-reason"
            className="time-select"
            style={{ width: "100%" }}
            value={reason}
            onChange={(e) => setReason(e.target.value as WasteReason)}
          >
            {REASONS.map((r) => (
              <option key={r.key} value={r.key}>
                {r.label}
              </option>
            ))}
          </select>
          {reasonMeta?.caption && (
            <div className="inline-note" style={{ marginTop: 3 }}>
              {reasonMeta.caption}
            </div>
          )}
        </div>
        <div className="rf-grid2">
          <div>
            <label className="field-label" htmlFor="wf-cost">
              Cost (£)
            </label>
            <input
              id="wf-cost"
              type="number"
              min={0}
              step="0.01"
              className="text-input"
              style={{ width: "100%" }}
              value={cost}
              onChange={(e) => setCost(e.target.value)}
            />
          </div>
          <div>
            <label className="field-label" htmlFor="wf-date">
              Date *
            </label>
            <input
              id="wf-date"
              type="date"
              className="text-input"
              style={{ width: "100%" }}
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </div>
        </div>
        <div>
          <label className="field-label" htmlFor="wf-notes">
            Notes
          </label>
          <input
            id="wf-notes"
            className="text-input"
            style={{ width: "100%" }}
            maxLength={255}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </div>
        <div className="inline-note">
          Waste entries can't be edited — log a correcting entry if you make a
          mistake.
        </div>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button className="btn btn-small" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-small btn-primary" onClick={save}>
            Log waste
          </button>
        </div>
      </div>
    </Modal>
  );
}
