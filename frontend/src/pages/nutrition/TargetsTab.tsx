/**
 * Targets tab — spec §4: the full TargetsDto ⇄ UpdateTargetsRequest editor.
 * Draft state is local; Save issues the full-replacement PUT with
 * expectedVersion = the loaded version. The editor re-keys on version so an
 * external change (e.g. an accepted health directive) resets the draft.
 *
 * Mock note: the 409 stale-version conflict card is NOT simulated — the
 * store accepts expectedVersion as-is (see saveTargets).
 */

import { useState } from "react";
import { saveTargets, useStore } from "../../mock/store";
import type {
  ActivityAdjustmentDto,
  CalorieTargetDto,
  EnforcementDirection,
  Goal,
  MacroTargetDto,
  MicroTargetDto,
  PerMealDistributionDto,
  TargetsDto,
} from "../../mock/types";
import { mealSlotLabel, microLabel, microUnit, parseNum, Switch } from "./shared";

const GOALS: Array<{ value: Goal; label: string }> = [
  { value: "LOSE_WEIGHT", label: "Lose weight" },
  { value: "MAINTAIN", label: "Maintain" },
  { value: "GAIN_WEIGHT", label: "Gain weight" },
];

const DIRECTIONS: Array<{ value: EnforcementDirection; label: string }> = [
  { value: "UPPER_LIMIT", label: "upper limit" },
  { value: "LOWER_FLOOR", label: "lower floor" },
  { value: "BOTH_BOUNDED", label: "both bounded" },
];

const ENFORCEMENTS = [
  { value: "DAILY", label: "daily" },
  { value: "WEEKLY_AVERAGE", label: "weekly average" },
];

type MacroFieldKey = "protein" | "carbs" | "fat" | "fibre" | "satFat";

const MACRO_ROWS: Array<{ key: MacroFieldKey; label: string }> = [
  { key: "protein", label: "Protein" },
  { key: "carbs", label: "Carbs" },
  { key: "fat", label: "Fat" },
  { key: "fibre", label: "Fibre" },
  { key: "satFat", label: "Sat fat" },
];

function DirectionSelect({
  value,
  onChange,
  label,
}: {
  value: EnforcementDirection;
  onChange: (v: EnforcementDirection) => void;
  label: string;
}) {
  return (
    <select
      className="time-select"
      value={value}
      onChange={(e) => onChange(e.target.value as EnforcementDirection)}
      aria-label={label}
    >
      {DIRECTIONS.map((d) => (
        <option key={d.value} value={d.value}>
          {d.label}
        </option>
      ))}
    </select>
  );
}

function EnforcementSelect({
  value,
  onChange,
  label,
}: {
  value: string | null | undefined;
  onChange: (v: string) => void;
  label: string;
}) {
  return (
    <select
      className="time-select"
      value={value ?? "DAILY"}
      onChange={(e) => onChange(e.target.value)}
      aria-label={label}
    >
      {ENFORCEMENTS.map((d) => (
        <option key={d.value} value={d.value}>
          {d.label}
        </option>
      ))}
    </select>
  );
}

function NumCell({
  value,
  onChange,
  label,
  width = 86,
}: {
  value: number | null | undefined;
  onChange: (v: number | null) => void;
  label: string;
  width?: number;
}) {
  return (
    <input
      type="number"
      className="text-input"
      style={{ width, padding: "6px 10px" }}
      value={value ?? ""}
      onChange={(e) => onChange(parseNum(e.target.value))}
      aria-label={label}
    />
  );
}

function TargetsEditor({
  targets,
  onSaved,
}: {
  targets: TargetsDto;
  onSaved: () => void;
}) {
  const [goal, setGoal] = useState<Goal>(targets.goal);
  const [calories, setCalories] = useState<CalorieTargetDto>(targets.calories);
  const [macros, setMacros] = useState<Record<MacroFieldKey, MacroTargetDto>>({
    protein: targets.protein,
    carbs: targets.carbs,
    fat: targets.fat,
    fibre: targets.fibre,
    satFat: targets.satFat,
  });
  const [perMeal, setPerMeal] = useState<PerMealDistributionDto[]>(
    targets.perMealDistribution,
  );
  const [microTargets, setMicroTargets] = useState<MicroTargetDto[]>(
    targets.microTargets,
  );
  const [eatingWindow, setEatingWindow] = useState(
    targets.eatingWindow ?? {
      enabled: false,
      windowStart: "08:00",
      windowEnd: "20:00",
      notes: null,
    },
  );
  const [adjustments, setAdjustments] = useState<ActivityAdjustmentDto[]>(
    targets.activityAdjustments,
  );
  const [notes, setNotes] = useState("");

  const setMacro = (key: MacroFieldKey, patch: Partial<MacroTargetDto>) =>
    setMacros((m) => ({ ...m, [key]: { ...m[key], ...patch } }));

  const save = () => {
    saveTargets({
      goal,
      calories,
      protein: macros.protein,
      carbs: macros.carbs,
      fat: macros.fat,
      fibre: macros.fibre,
      satFat: macros.satFat,
      notes: notes.trim() ? notes.trim().slice(0, 512) : null,
      perMealDistribution: perMeal,
      microTargets,
      eatingWindow,
      activityAdjustments: adjustments,
      expectedVersion: targets.version,
    });
    onSaved();
  };

  return (
    <div>
      {/* Goal */}
      <div className="mp-card section-card">
        <span className="mp-label">Goal</span>
        <div className="filter-row" style={{ marginTop: 10 }}>
          {GOALS.map((g) => (
            <button
              key={g.value}
              className={`filter-chip${goal === g.value ? " active" : ""}`}
              onClick={() => setGoal(g.value)}
            >
              {g.label}
            </button>
          ))}
        </div>
      </div>

      {/* Calories */}
      <div className="mp-card section-card">
        <span className="mp-label">Calories</span>
        <div className="targets-row" style={{ marginTop: 8 }}>
          <span className="pantry-stepper">
            <button
              className="stepper-btn"
              aria-label="Decrease daily calorie target"
              onClick={() =>
                setCalories((c) => ({
                  ...c,
                  dailyTarget: Math.max(1000, c.dailyTarget - 50),
                }))
              }
            >
              −
            </button>
            <span className="target-value">
              <span className="mp-num" style={{ fontSize: 19 }}>
                {calories.dailyTarget.toLocaleString("en-GB")}
              </span>{" "}
              <span className="inline-note">kcal / day</span>
            </span>
            <button
              className="stepper-btn"
              aria-label="Increase daily calorie target"
              onClick={() =>
                setCalories((c) => ({
                  ...c,
                  dailyTarget: Math.min(5000, c.dailyTarget + 50),
                }))
              }
            >
              +
            </button>
          </span>
          <label style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
            <span className="inline-note">tolerance −</span>
            <NumCell
              value={calories.toleranceUnder}
              onChange={(v) =>
                setCalories((c) => ({ ...c, toleranceUnder: v ?? 0 }))
              }
              label="Tolerance under"
              width={70}
            />
          </label>
          <label style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
            <span className="inline-note">tolerance +</span>
            <NumCell
              value={calories.toleranceOver}
              onChange={(v) =>
                setCalories((c) => ({ ...c, toleranceOver: v ?? 0 }))
              }
              label="Tolerance over"
              width={70}
            />
          </label>
          <DirectionSelect
            value={calories.direction}
            onChange={(v) => setCalories((c) => ({ ...c, direction: v }))}
            label="Calories direction"
          />
          <EnforcementSelect
            value={calories.enforcement}
            onChange={(v) => setCalories((c) => ({ ...c, enforcement: v }))}
            label="Calories enforcement"
          />
        </div>
      </div>

      {/* Macro rows */}
      <div className="mp-card section-card">
        <span className="mp-label">Macros — absolute grams, not ratios</span>
        <table className="nv-table" style={{ marginTop: 10 }}>
          <thead>
            <tr>
              <th>Macro</th>
              <th>target g</th>
              <th>floor g</th>
              <th>direction</th>
              <th>enforcement</th>
              <th title="participates in the planner's hard-floor gate">
                hard floor ▪
              </th>
            </tr>
          </thead>
          <tbody>
            {MACRO_ROWS.map(({ key, label }) => {
              const m = macros[key];
              const custom = targets.userOverriddenDirections.includes(key);
              return (
                <tr key={key}>
                  <td>
                    {label}
                    {custom && (
                      <span
                        className="tint-chip terra"
                        style={{ marginLeft: 8 }}
                        title="direction changed by you"
                      >
                        custom
                      </span>
                    )}
                  </td>
                  <td>
                    <NumCell
                      value={m.targetG}
                      onChange={(v) => setMacro(key, { targetG: v })}
                      label={`${label} target g`}
                    />
                  </td>
                  <td>
                    <NumCell
                      value={m.floorG}
                      onChange={(v) => setMacro(key, { floorG: v })}
                      label={`${label} floor g`}
                    />
                  </td>
                  <td>
                    <DirectionSelect
                      value={m.direction}
                      onChange={(v) => setMacro(key, { direction: v })}
                      label={`${label} direction`}
                    />
                  </td>
                  <td>
                    <EnforcementSelect
                      value={m.enforcement}
                      onChange={(v) => setMacro(key, { enforcement: v })}
                      label={`${label} enforcement`}
                    />
                  </td>
                  <td>
                    <Switch
                      on={m.isHardFloor}
                      onToggle={() =>
                        setMacro(key, { isHardFloor: !m.isHardFloor })
                      }
                      label={`${label} hard floor`}
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Per-meal distribution */}
      <div className="mp-card section-card">
        <span className="mp-label">Per-meal distribution</span>
        <table className="nv-table" style={{ marginTop: 10 }}>
          <thead>
            <tr>
              <th>Slot</th>
              <th>kcal target</th>
              <th>protein target g</th>
            </tr>
          </thead>
          <tbody>
            {perMeal.map((row, i) => (
              <tr key={row.mealSlot}>
                <td>{mealSlotLabel(row.mealSlot)}</td>
                <td>
                  <NumCell
                    value={row.calorieTarget}
                    onChange={(v) =>
                      setPerMeal((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, calorieTarget: v ?? 0 } : r,
                        ),
                      )
                    }
                    label={`${row.mealSlot} calorie target`}
                  />
                </td>
                <td>
                  <NumCell
                    value={row.proteinTargetG}
                    onChange={(v) =>
                      setPerMeal((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, proteinTargetG: v ?? 0 } : r,
                        ),
                      )
                    }
                    label={`${row.mealSlot} protein target`}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="inline-note" style={{ marginTop: 8 }}>
          guideline, the planner may redistribute
        </div>
      </div>

      {/* Eating window */}
      <div className="mp-card section-card">
        <span className="mp-label">Eating window</span>
        <div className="targets-row" style={{ marginTop: 8 }}>
          <Switch
            on={eatingWindow.enabled}
            onToggle={() =>
              setEatingWindow((w) => ({ ...w, enabled: !w.enabled }))
            }
            label="Eating window enabled"
          />
          <input
            type="time"
            className="time-select"
            value={eatingWindow.windowStart ?? ""}
            disabled={!eatingWindow.enabled}
            onChange={(e) =>
              setEatingWindow((w) => ({ ...w, windowStart: e.target.value }))
            }
            aria-label="Window start"
          />
          <span className="inline-note">to</span>
          <input
            type="time"
            className="time-select"
            value={eatingWindow.windowEnd ?? ""}
            disabled={!eatingWindow.enabled}
            onChange={(e) =>
              setEatingWindow((w) => ({ ...w, windowEnd: e.target.value }))
            }
            aria-label="Window end"
          />
          <input
            type="text"
            className="text-input"
            style={{ flex: 1, minWidth: 180 }}
            placeholder="Notes (e.g. 14:10 fasting pattern)"
            value={eatingWindow.notes ?? ""}
            onChange={(e) =>
              setEatingWindow((w) => ({ ...w, notes: e.target.value || null }))
            }
            aria-label="Eating window notes"
          />
        </div>
      </div>

      {/* Activity adjustments */}
      <div className="mp-card section-card">
        <span className="mp-label">Activity adjustments</span>
        <table className="nv-table" style={{ marginTop: 10 }}>
          <thead>
            <tr>
              <th>Level</th>
              <th>kcal modifier</th>
              <th>carb modifier g</th>
            </tr>
          </thead>
          <tbody>
            {adjustments.map((row, i) => (
              <tr key={row.activityLevel}>
                <td>{row.activityLevel.toLowerCase().replace(/_/g, " ")}</td>
                <td>
                  <NumCell
                    value={row.calorieModifier}
                    onChange={(v) =>
                      setAdjustments((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, calorieModifier: v ?? 0 } : r,
                        ),
                      )
                    }
                    label={`${row.activityLevel} calorie modifier`}
                  />
                </td>
                <td>
                  <NumCell
                    value={row.carbModifierG}
                    onChange={(v) =>
                      setAdjustments((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, carbModifierG: v ?? 0 } : r,
                        ),
                      )
                    }
                    label={`${row.activityLevel} carb modifier`}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Micro targets */}
      <div className="mp-card section-card">
        <span className="mp-label">Micronutrient targets</span>
        <table className="nv-table" style={{ marginTop: 10 }}>
          <thead>
            <tr>
              <th>Nutrient</th>
              <th>target</th>
              <th>upper limit</th>
              <th>hard floor ▪</th>
              <th>notes</th>
            </tr>
          </thead>
          <tbody>
            {microTargets.map((row, i) => (
              <tr key={row.nutrientKey}>
                <td>
                  {microLabel(row.nutrientKey)}{" "}
                  <span className="inline-note">
                    {microUnit(row.nutrientKey)}
                  </span>
                </td>
                <td>
                  <NumCell
                    value={row.targetValue}
                    onChange={(v) =>
                      setMicroTargets((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, targetValue: v } : r,
                        ),
                      )
                    }
                    label={`${row.nutrientKey} target`}
                    width={78}
                  />
                </td>
                <td>
                  <NumCell
                    value={row.upperLimit}
                    onChange={(v) =>
                      setMicroTargets((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, upperLimit: v } : r,
                        ),
                      )
                    }
                    label={`${row.nutrientKey} upper limit`}
                    width={78}
                  />
                </td>
                <td>
                  <Switch
                    on={row.isHardFloor}
                    onToggle={() =>
                      setMicroTargets((rows) =>
                        rows.map((r, ri) =>
                          ri === i ? { ...r, isHardFloor: !r.isHardFloor } : r,
                        ),
                      )
                    }
                    label={`${row.nutrientKey} hard floor`}
                  />
                </td>
                <td>
                  <input
                    type="text"
                    className="text-input"
                    style={{ width: "100%", minWidth: 140, padding: "6px 10px" }}
                    value={row.notes ?? ""}
                    onChange={(e) =>
                      setMicroTargets((rows) =>
                        rows.map((r, ri) =>
                          ri === i
                            ? { ...r, notes: e.target.value || null }
                            : r,
                        ),
                      )
                    }
                    aria-label={`${row.nutrientKey} notes`}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="inline-note" style={{ marginTop: 8 }}>
          {microTargets.length} of ≤30 rows · add/remove arrives with the real
          editor
        </div>
      </div>

      {/* Save */}
      <div
        className="mp-card section-card"
        style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}
      >
        <input
          type="text"
          className="text-input"
          style={{ flex: 1, minWidth: 220 }}
          placeholder="Change note (optional, ≤512)"
          maxLength={512}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          aria-label="Change note"
        />
        <span className="inline-note">
          full-replacement PUT · expectedVersion v{targets.version}
        </span>
        <button className="btn btn-primary" onClick={save}>
          Save targets
        </button>
      </div>
      <div className="inline-note" style={{ marginTop: 6 }}>
        Hard floors drive the planner's feasibility gate. 409 stale-version
        conflicts are not simulated in the mock.
      </div>
    </div>
  );
}

export function TargetsTab() {
  const targets = useStore((s) => s.targets);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  return (
    <div>
      {savedAt !== null && (
        <div
          className="tint-chip olive"
          style={{ display: "inline-block", marginTop: 16 }}
          role="status"
        >
          ✓ saved — now v{targets.version}
        </div>
      )}
      {/* Re-key on version: external edits (accepted directives) reset the draft. */}
      <TargetsEditor
        key={targets.version}
        targets={targets}
        onSaved={() => setSavedAt(Date.now())}
      />
    </div>
  );
}
