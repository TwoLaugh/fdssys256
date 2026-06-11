import { useState } from "react";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { TintChip } from "../components/TintChip";
import {
  adjustPortionScale,
  adjustSlotTime,
  adjustWeeklyBudget,
  refreshTasteProfile,
  removeConstraint,
  rollbackTasteProfile,
  useStore,
} from "../mock/store";
import type { ConstraintKind, MealSlotKey } from "../mock/types";

const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

interface RemovalTarget {
  kind: ConstraintKind;
  name: string;
}

/**
 * GAP-04 interstitial: removing a hard constraint is never a one-step edit.
 * The destructive confirm stays disabled until the user types the exact
 * constraint name.
 */
function RemoveConstraintDialog({
  target,
  onClose,
}: {
  target: RemovalTarget;
  onClose: () => void;
}) {
  const [typed, setTyped] = useState("");
  const match = typed.trim().toLowerCase() === target.name.toLowerCase();
  const noun = target.kind === "allergy" ? "allergen" : "dietary identity";

  return (
    <Modal label={`Remove ${target.name}`} onClose={onClose}>
      <span className="mp-label" style={{ color: "var(--mp-red)" }}>
        Remove hard constraint
      </span>
      <h2 className="dialog-title">
        Remove {target.name.toLowerCase()} from{" "}
        {target.kind === "allergy" ? "allergies" : "dietary identities"}?
      </h2>
      <p className="dialog-body">
        Removing {target.kind === "allergy" ? "an allergy" : "a dietary identity"}{" "}
        affects safety filtering for every plan, recipe and grocery list. Type
        the {noun} name to confirm.
      </p>
      <input
        type="text"
        className="text-input"
        style={{ width: "100%", marginTop: 4 }}
        placeholder={target.name}
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        aria-label={`Type ${target.name} to confirm removal`}
        autoFocus
      />
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-danger"
          disabled={!match}
          title={match ? undefined : `Type “${target.name}” to enable`}
          onClick={() => {
            removeConstraint(target.kind, target.name);
            onClose();
          }}
        >
          Remove {noun}
        </button>
      </div>
    </Modal>
  );
}

function ConstraintChip({
  name,
  kind,
  onRemove,
}: {
  name: string;
  kind: ConstraintKind;
  onRemove: (target: RemovalTarget) => void;
}) {
  return (
    <span className="constraint-chip">
      {name}
      <button
        type="button"
        className="chip-x"
        aria-label={`Remove ${name}`}
        onClick={() => onRemove({ kind, name })}
      >
        ✕
      </button>
    </span>
  );
}

export function Preferences() {
  const prefs = useStore((s) => s.preferences);
  const [removal, setRemoval] = useState<RemovalTarget | null>(null);

  const v = prefs.profileVersion;

  return (
    <div>
      <PageHeader
        title="Taste & preferences"
        meta="What the advisor has learned, your hard constraints, and lifestyle configuration"
        actions={
          <>
            <button
              className="btn"
              onClick={rollbackTasteProfile}
              disabled={prefs.refreshing || v <= 3}
              title={v <= 3 ? "No earlier version retained" : undefined}
            >
              Roll back
            </button>
            <button
              className="btn btn-primary"
              onClick={refreshTasteProfile}
              disabled={prefs.refreshing}
            >
              {prefs.refreshing ? "Refreshing…" : "Refresh now"}
            </button>
          </>
        }
      />

      <div className="version-strip">
        <span className="mp-label">Profile version</span>
        <span className="version-current">v{v} current</span>
        <span className="version-old">v{v - 1}</span>
        <span className="version-old">v{v - 2}</span>
        <span className="version-note">
          rebuilt from feedback signals · roll back restores the previous
          version
        </span>
      </div>

      <div style={{ marginTop: 22 }}>
        <span className="mp-serif" style={{ fontSize: 23 }}>
          Here's what I think you like — built from 142 feedback signals.
        </span>
      </div>

      <div className="pref-grid">
        {prefs.groups.map((group) => (
          <div key={group.name} className="mp-card side-card">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              {group.name}
            </span>
            <div className="pref-chip-block">
              <span className="mp-label">Likes</span>
              <div className="pref-chips">
                {group.likes.map((like) => (
                  <TintChip key={like}>{like}</TintChip>
                ))}
              </div>
            </div>
            <div className="pref-chip-block">
              <span className="mp-label">Dislikes</span>
              <div className="pref-chips">
                {group.dislikes.map((dislike) => (
                  <span key={dislike} className="tier-badge">
                    {dislike}
                  </span>
                ))}
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="pref-bottom">
        <div className="mp-card side-card">
          <div style={{ display: "flex", justifyContent: "space-between" }}>
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Hard constraints
            </span>
            <span className="mp-label" style={{ color: "var(--mp-red)" }}>
              Safety filtered
            </span>
          </div>
          <div className="pref-chip-block">
            <span className="mp-label">Allergies</span>
            <div className="pref-chips">
              {prefs.allergies.length === 0 ? (
                <span className="intake-meta">None recorded.</span>
              ) : (
                prefs.allergies.map((a) => (
                  <ConstraintChip
                    key={a}
                    name={a}
                    kind="allergy"
                    onRemove={setRemoval}
                  />
                ))
              )}
            </div>
          </div>
          <div className="pref-chip-block">
            <span className="mp-label">Dietary identity</span>
            <div className="pref-chips">
              {prefs.dietary.length === 0 ? (
                <span className="intake-meta">None recorded.</span>
              ) : (
                prefs.dietary.map((d) => (
                  <ConstraintChip
                    key={d}
                    name={d}
                    kind="dietary"
                    onRemove={setRemoval}
                  />
                ))
              )}
            </div>
          </div>
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            These are never broken by any plan. Removing one requires explicit
            confirmation.
          </div>
        </div>

        <div className="mp-card side-card">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Lifestyle
          </span>
          <div style={{ marginTop: 6 }}>
            {SLOT_KEYS.map((slot) => (
              <div key={slot} className="target-row">
                <span className="target-label" style={{ textTransform: "capitalize" }}>
                  {slot} time
                </span>
                <span className="pantry-stepper">
                  <button
                    className="stepper-btn"
                    aria-label={`Earlier ${slot}`}
                    onClick={() => adjustSlotTime(slot, -1)}
                  >
                    −
                  </button>
                  <span className="target-value">
                    <span className="mp-num" style={{ fontSize: 16 }}>
                      {prefs.lifestyle.slotTimes[slot]}
                    </span>
                  </span>
                  <button
                    className="stepper-btn"
                    aria-label={`Later ${slot}`}
                    onClick={() => adjustSlotTime(slot, 1)}
                  >
                    +
                  </button>
                </span>
              </div>
            ))}
            <div className="target-row">
              <span className="target-label">Portion scale</span>
              <span className="pantry-stepper">
                <button
                  className="stepper-btn"
                  aria-label="Decrease portion scale"
                  onClick={() => adjustPortionScale(-1)}
                >
                  −
                </button>
                <span className="target-value">
                  <span className="mp-num" style={{ fontSize: 16 }}>
                    {prefs.lifestyle.portionScale.toFixed(1)}×
                  </span>
                </span>
                <button
                  className="stepper-btn"
                  aria-label="Increase portion scale"
                  onClick={() => adjustPortionScale(1)}
                >
                  +
                </button>
              </span>
            </div>
            <div className="target-row">
              <span className="target-label">Weekly budget</span>
              <span className="pantry-stepper">
                <button
                  className="stepper-btn"
                  aria-label="Decrease weekly budget"
                  onClick={() => adjustWeeklyBudget(-1)}
                >
                  −
                </button>
                <span className="target-value">
                  <span className="mp-num" style={{ fontSize: 16 }}>
                    £{prefs.lifestyle.weeklyBudget}
                  </span>
                </span>
                <button
                  className="stepper-btn"
                  aria-label="Increase weekly budget"
                  onClick={() => adjustWeeklyBudget(1)}
                >
                  +
                </button>
              </span>
            </div>
          </div>
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            Slot times feed Today's timeline; the budget drives pantry and
            grocery headroom.
          </div>
        </div>
      </div>

      {removal && (
        <RemoveConstraintDialog
          target={removal}
          onClose={() => setRemoval(null)}
        />
      )}
    </div>
  );
}
