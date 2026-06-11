import { useState } from "react";
import type { NutritionStat } from "../api";
import { PageHeader } from "../components/PageHeader";
import { SegmentBar } from "../components/SegmentBar";
import { StatBand } from "../components/StatBand";
import {
  addJournalEntry,
  adjustTarget,
  confirmIntake,
  logSnack,
  skipIntake,
  useStore,
} from "../mock/store";
import type { IntakeSlot, MacroKey } from "../mock/types";

const QUICK_SNACKS: Array<{ name: string; kcal: number }> = [
  { name: "Banana", kcal: 105 },
  { name: "Greek yoghurt", kcal: 150 },
  { name: "Protein bar", kcal: 210 },
  { name: "Oat flapjack", kcal: 190 },
];

const TARGET_ROWS: Array<{ key: MacroKey; label: string; unit: string }> = [
  { key: "calories", label: "Calories", unit: "kcal" },
  { key: "protein", label: "Protein", unit: "g" },
  { key: "carbs", label: "Carbs", unit: "g" },
  { key: "fat", label: "Fat", unit: "g" },
];

function IntakeRow({ entry }: { entry: IntakeSlot }) {
  const meta = useStore((s) => s.today.slotMeta[entry.slot]);
  const dish = useStore(
    (s) => s.plan.days.find((d) => d.today)?.slots[entry.slot].name,
  );
  const [editing, setEditing] = useState(false);
  const [kcalText, setKcalText] = useState("");

  const save = () => {
    const kcal = parseInt(kcalText, 10);
    if (!Number.isNaN(kcal) && kcal > 0 && kcal < 3000) {
      confirmIntake(entry.slot, kcal);
      setEditing(false);
    }
  };

  return (
    <div className="intake-row">
      <div>
        <span className="mp-num" style={{ fontSize: 19 }}>
          {meta.time}
        </span>
        <div style={{ marginTop: 3 }}>
          <span className="mp-label">{entry.slot}</span>
        </div>
      </div>
      <div style={{ minWidth: 0 }}>
        <span className="intake-name">{dish ?? "—"}</span>
        <div className="intake-meta">planned {entry.plannedKcal} kcal</div>
      </div>
      <div className="intake-actions">
        {entry.status === "confirmed" && (
          <span className="intake-state confirmed">
            ✓ {entry.actualKcal} kcal logged
            {entry.actualKcal !== entry.plannedKcal && " · edited"}
          </span>
        )}
        {entry.status === "skipped" && (
          <span className="intake-state skipped">— skipped · 0 kcal</span>
        )}
        {entry.status === "pending" &&
          (editing ? (
            <span style={{ display: "inline-flex", gap: 8, alignItems: "center" }}>
              <input
                type="number"
                className="text-input kcal-input"
                value={kcalText}
                onChange={(e) => setKcalText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") save();
                  if (e.key === "Escape") setEditing(false);
                }}
                aria-label={`Actual kcal for ${entry.slot}`}
                autoFocus
              />
              <button className="btn btn-small btn-primary" onClick={save}>
                Save
              </button>
              <button
                className="btn btn-small"
                onClick={() => setEditing(false)}
              >
                Cancel
              </button>
            </span>
          ) : (
            <span style={{ display: "inline-flex", gap: 8 }}>
              <button
                className="btn btn-small btn-primary"
                onClick={() => confirmIntake(entry.slot)}
              >
                Confirm
              </button>
              <button
                className="btn btn-small"
                onClick={() => {
                  setKcalText(String(entry.plannedKcal));
                  setEditing(true);
                }}
              >
                Edit kcal
              </button>
              <button
                className="btn btn-small"
                onClick={() => skipIntake(entry.slot)}
              >
                Skip
              </button>
            </span>
          ))}
      </div>
    </div>
  );
}

export function Nutrition() {
  const nutrition = useStore((s) => s.nutrition);
  const todayMacros = useStore((s) => s.today.nutrition);
  const targets = useStore((s) => s.targets);
  const [note, setNote] = useState("");
  const [snackOpen, setSnackOpen] = useState(false);

  const stats: NutritionStat[] = todayMacros.map((n) => ({
    label: n.label,
    value: n.value,
    target: n.target,
    display: n.value.toLocaleString("en-GB"),
    targetDisplay: `${n.target.toLocaleString("en-GB")}${n.unit}`,
    behind: n.behind,
  }));

  const todayKcal =
    todayMacros.find((n) => n.label === "Calories")?.value ?? 0;
  const snackTotal = nutrition.snacks.reduce((acc, s) => acc + s.kcal, 0);

  const addNote = () => {
    addJournalEntry(note);
    setNote("");
  };

  return (
    <div>
      <PageHeader
        title="Nutrition"
        meta="Wednesday 10 June · planned vs actual intake — confirming a slot credits its calories"
      />

      <StatBand stats={stats} />

      <div className="week-strip mp-card" aria-label="This week vs target">
        {nutrition.week.map((d) => {
          const kcal = d.today ? todayKcal : d.kcal;
          const pct = targets.calories > 0 ? kcal / targets.calories : 0;
          return (
            <div
              key={d.day}
              className={`week-cell${d.today ? " today" : ""}`}
            >
              <span
                className="mp-label"
                style={d.today ? { color: "var(--mp-terra)" } : undefined}
              >
                {d.day}
              </span>
              <div className="week-kcal">
                {kcal > 0 ? (
                  <span className="mp-num" style={{ fontSize: 15 }}>
                    {kcal.toLocaleString("en-GB")}
                  </span>
                ) : (
                  <span style={{ color: "var(--mp-muted)", fontSize: 12 }}>
                    —
                  </span>
                )}
              </div>
              <SegmentBar
                pct={pct}
                segments={12}
                tone={d.today && pct < 0.55 ? "amber" : "olive"}
              />
            </div>
          );
        })}
      </div>

      <div className="nutrition-layout">
        <div>
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Today's slots
            </span>
          </div>
          {nutrition.intake.map((entry) => (
            <IntakeRow key={entry.slot} entry={entry} />
          ))}

          <div className="group-head" style={{ marginTop: 26 }}>
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Snacks · {snackTotal} kcal
            </span>
          </div>
          {nutrition.snacks.length === 0 ? (
            <div className="intake-meta" style={{ padding: "10px 0" }}>
              Nothing logged yet.
            </div>
          ) : (
            nutrition.snacks.map((snack, i) => (
              <div key={`${snack.name}-${i}`} className="snack-row">
                <span>{snack.name}</span>
                <span className="snack-kcal">{snack.kcal} kcal</span>
              </div>
            ))
          )}
          {snackOpen ? (
            <div className="snack-chips">
              {QUICK_SNACKS.map((snack) => (
                <button
                  key={snack.name}
                  className="filter-chip"
                  onClick={() => {
                    logSnack(snack.name, snack.kcal);
                    setSnackOpen(false);
                  }}
                >
                  {snack.name} · {snack.kcal} kcal
                </button>
              ))}
              <button className="filter-chip" onClick={() => setSnackOpen(false)}>
                Cancel
              </button>
            </div>
          ) : (
            <button
              className="btn"
              style={{ marginTop: 14 }}
              onClick={() => setSnackOpen(true)}
            >
              + Log a snack
            </button>
          )}
        </div>

        <div style={{ display: "grid", gap: 18, alignContent: "start" }}>
          <div className="mp-card side-card">
            <span className="mp-label">Daily targets</span>
            <div style={{ marginTop: 6 }}>
              {TARGET_ROWS.map(({ key, label, unit }) => (
                <div key={key} className="target-row">
                  <span className="target-label">{label}</span>
                  <span className="pantry-stepper">
                    <button
                      className="stepper-btn"
                      aria-label={`Decrease ${label} target`}
                      onClick={() => adjustTarget(key, -1)}
                    >
                      −
                    </button>
                    <span className="target-value">
                      <span className="mp-num" style={{ fontSize: 17 }}>
                        {targets[key].toLocaleString("en-GB")}
                      </span>{" "}
                      <span style={{ color: "var(--mp-muted)", fontSize: 12 }}>
                        {unit}
                      </span>
                    </span>
                    <button
                      className="stepper-btn"
                      aria-label={`Increase ${label} target`}
                      onClick={() => adjustTarget(key, 1)}
                    >
                      +
                    </button>
                  </span>
                </div>
              ))}
            </div>
            <div className="grocery-footnote" style={{ marginTop: 12 }}>
              Edits apply everywhere immediately — Today's stat band reads the
              same targets.
            </div>
          </div>

          <div className="mp-card side-card">
            <span className="mp-label">Food & mood journal</span>
            <div style={{ marginTop: 10 }}>
              {nutrition.journal.map((entry, i) => (
                <div key={`${entry.when}-${i}`} className="journal-row">
                  <div className="journal-meta">
                    <span>{entry.when}</span>
                    {entry.mood && (
                      <span className="tint-chip olive">{entry.mood}</span>
                    )}
                  </div>
                  <div className="journal-text">{entry.text}</div>
                </div>
              ))}
            </div>
            <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
              <input
                type="text"
                className="text-input"
                style={{ flex: 1, minWidth: 0 }}
                placeholder="Add a note — how did food feel today?"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && addNote()}
                aria-label="Add journal note"
              />
              <button
                className="btn btn-small"
                onClick={addNote}
                disabled={!note.trim()}
              >
                Add
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
