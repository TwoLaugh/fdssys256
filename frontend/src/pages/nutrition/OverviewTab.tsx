/**
 * Overview tab — spec §3: day header + activity quick control, divergence
 * advisor banner, six-cell stat band, week strip with floor-violation chips,
 * slot rows (planned vs actual), snacks with lookup-assisted add form,
 * journal, collapsed micros panel.
 */

import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { AdvisorCard } from "../../components/AdvisorCard";
import { Modal } from "../../components/Modal";
import { NutrientRow } from "../../components/NutrientRow";
import { SegmentBar } from "../../components/SegmentBar";
import { QUICK_SNACKS } from "../../mock/nutritionSeed";
// Live-aware date anchors (real clock in live mode) — see src/live/dates.ts.
import {
  CURRENT_WEEK_START,
  MOCK_TODAY_ISO,
  TODAY_INDEX,
  WEEK_DATES,
  WEEK_DAY_LABELS,
} from "../../live/dates";
import {
  activePlanForWeek,
  addJournalEntry,
  addSnack,
  computeDailyAggregate,
  computeDivergence,
  computeWeeklyAggregate,
  confirmSlot,
  deleteJournalEntry,
  editSlot,
  macroWarn,
  overrideSlot,
  recipeName,
  removeSnack,
  searchIngredients,
  selectSlotTimes,
  skipSlot,
  updateJournalEntry,
  upsertActivity,
  useStore,
} from "../../mock/store";
import type {
  ActivityLevel,
  DailyAggregateDto,
  EnforcementDirection,
  FloorViolationDto,
  FoodMoodEntryDto,
  IngredientNutritionDto,
  IntakeSlotDto,
  IntakeSource,
  LogSnackRequest,
  MealSlot,
  MealSlotKey,
  TargetsDto,
} from "../../mock/types";
import {
  fmtG,
  fmtKcal,
  MacroLine,
  mealSlotLabel,
  microLabel,
  MicroRowsEditor,
  microUnit,
  prettyDate,
  rowsFromMicros,
  microsFromRows,
  shortDate,
  shortTime,
  SlotStateChip,
  SourceBadge,
  Switch,
  type MicroRow,
} from "./shared";
import { TargetsEmptyState } from "./TargetsEmptyState";

/* ---- activity quick control (spec §3a) -------------------------------------- */

const ACTIVITY_LEVELS: ActivityLevel[] = [
  "REST_DAY",
  "LIGHT_ACTIVITY",
  "TRAINING_DAY",
  "HEAVY_TRAINING",
];

const ACTIVITY_LABEL: Record<ActivityLevel, string> = {
  REST_DAY: "Rest day",
  LIGHT_ACTIVITY: "Light activity",
  TRAINING_DAY: "Training day",
  HEAVY_TRAINING: "Heavy training",
};

const ACTIVITY_BADGE: Record<ActivityLevel, string> = {
  REST_DAY: "R",
  LIGHT_ACTIVITY: "L",
  TRAINING_DAY: "T",
  HEAVY_TRAINING: "H",
};

// Stable empty fallback so the selector keeps a constant reference when
// targets are not initialised (useSyncExternalStore snapshot rule).
const EMPTY_ADJUSTMENTS: TargetsDto["activityAdjustments"] = [];

function ActivityControl({ date }: { date: string }) {
  const entry = useStore((s) => s.nutrition.dailyActivity[date]);
  const adjustments = useStore(
    (s) => s.targets?.activityAdjustments ?? EMPTY_ADJUSTMENTS,
  );
  const [open, setOpen] = useState(false);
  const [notes, setNotes] = useState("");
  const current = entry?.activityLevel ?? "LIGHT_ACTIVITY";
  const adj = adjustments.find((a) => a.activityLevel === current);

  const signed = (n: number): string => `${n > 0 ? "+" : ""}${n}`;

  return (
    <div className="activity-anchor">
      <button
        className="filter-chip"
        aria-expanded={open}
        onClick={() => {
          setNotes(entry?.notes ?? "");
          setOpen((o) => !o);
        }}
      >
        {ACTIVITY_LABEL[current]}
        {entry ? "" : " · default"} ▾
      </button>
      {entry && adj && adj.calorieModifier !== 0 && (
        <div className="inline-note" style={{ marginTop: 4, textAlign: "right" }}>
          {signed(adj.calorieModifier)} kcal · {signed(adj.carbModifierG)} g
          carbs applied
        </div>
      )}
      {open && (
        <div className="activity-pop mp-card">
          <span className="mp-label">Activity level · {shortDate(date)}</span>
          <div style={{ display: "grid", gap: 6, marginTop: 10 }}>
            {ACTIVITY_LEVELS.map((lv) => {
              const a = adjustments.find((x) => x.activityLevel === lv);
              return (
                <button
                  key={lv}
                  className={`filter-chip${lv === current ? " active" : ""}`}
                  style={{ justifyContent: "space-between", display: "flex" }}
                  onClick={() => upsertActivity(date, lv, notes)}
                >
                  <span>{ACTIVITY_LABEL[lv]}</span>
                  {a && (
                    <span className="inline-note">
                      {signed(a.calorieModifier)} kcal
                    </span>
                  )}
                </button>
              );
            })}
          </div>
          <input
            type="text"
            className="text-input"
            style={{ width: "100%", marginTop: 10 }}
            placeholder="Notes (optional)"
            maxLength={255}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            aria-label="Activity notes"
          />
          <div className="modal-actions">
            <button
              className="btn btn-small btn-primary"
              onClick={() => {
                upsertActivity(date, current, notes);
                setOpen(false);
              }}
            >
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/* ---- six-cell stat band (spec §3b) --------------------------------------------- */

interface BandCell {
  label: string;
  actual: number;
  target: number;
  direction: EnforcementDirection;
  hardFloor: boolean;
  unit: "kcal" | "g";
}

function bandCells(agg: DailyAggregateDto, targets: TargetsDto): BandCell[] {
  return [
    {
      label: "Calories",
      actual: agg.caloriesActualSoFar,
      target: targets.calories.dailyTarget,
      direction: targets.calories.direction,
      hardFloor: false,
      unit: "kcal",
    },
    {
      label: "Protein",
      actual: agg.protein.actualSoFarG,
      target: targets.protein.targetG ?? 0,
      direction: targets.protein.direction,
      hardFloor: targets.protein.isHardFloor,
      unit: "g",
    },
    {
      label: "Carbs",
      actual: agg.carbs.actualSoFarG,
      target: targets.carbs.targetG ?? 0,
      direction: targets.carbs.direction,
      hardFloor: targets.carbs.isHardFloor,
      unit: "g",
    },
    {
      label: "Fat",
      actual: agg.fat.actualSoFarG,
      target: targets.fat.targetG ?? 0,
      direction: targets.fat.direction,
      hardFloor: targets.fat.isHardFloor,
      unit: "g",
    },
    {
      label: "Fibre",
      actual: agg.fibre.actualSoFarG,
      target: targets.fibre.targetG ?? 0,
      direction: targets.fibre.direction,
      hardFloor: targets.fibre.isHardFloor,
      unit: "g",
    },
    {
      // Backend gap: no satFat aggregate in DailyAggregateDto — read from
      // the micros map (flagged in the spec PR).
      label: "Sat fat",
      actual: agg.microsActualSoFar["saturated_fat_g"] ?? 0,
      target: targets.satFat.targetG ?? 0,
      direction: targets.satFat.direction,
      hardFloor: targets.satFat.isHardFloor,
      unit: "g",
    },
  ];
}

function StatBandSix({
  agg,
  targets,
}: {
  agg: DailyAggregateDto;
  targets: TargetsDto;
}) {
  return (
    <div className="stat-band cells-6 mp-card" style={{ marginTop: 18 }}>
      {bandCells(agg, targets).map((cell) => {
        const warn = macroWarn(cell.direction, cell.actual, cell.target);
        const over = cell.actual > cell.target;
        const fmt = cell.unit === "kcal" ? fmtKcal : fmtG;
        const remaining = over
          ? `${fmt(cell.actual - cell.target)} ${cell.unit} over`
          : `${fmt(cell.target - cell.actual)} ${cell.unit} left`;
        const suffix = warn ? (over ? " · over" : " · behind") : "";
        return (
          <div key={cell.label} className="stat-cell">
            <span
              className="mp-label"
              style={warn ? { color: "var(--mp-amber)" } : undefined}
            >
              {cell.label}
              {cell.hardFloor && (
                <span className="hard-floor-mark" title="hard floor">
                  ▪
                </span>
              )}
              {suffix}
            </span>
            <div className="stat-value">
              <span
                className="mp-num"
                style={{
                  fontSize: 27,
                  color: warn ? "var(--mp-amber)" : "var(--mp-ink)",
                }}
              >
                {fmt(cell.actual)}
              </span>
              <span className="stat-target">
                / {fmt(cell.target)}
                {cell.unit === "g" ? " g" : ""}
              </span>
            </div>
            <div className="stat-remaining">{remaining}</div>
            <SegmentBar
              pct={cell.target > 0 ? cell.actual / cell.target : 0}
              tone={warn ? "amber" : "olive"}
            />
          </div>
        );
      })}
    </div>
  );
}

/* ---- slot rows (spec §3d) -------------------------------------------------------- */

function OverrideModal({
  mealSlot,
  onSubmit,
  onClose,
}: {
  mealSlot: MealSlot;
  onSubmit: (text: string) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState("");
  const submit = () => {
    if (!text.trim()) return;
    onSubmit(text);
    onClose();
  };
  return (
    <Modal label={`Log what you ate — ${mealSlotLabel(mealSlot)}`} onClose={onClose}>
      <span className="mp-label">Log what I ate · {mealSlotLabel(mealSlot)}</span>
      <p className="dialog-body">
        Describe it in your own words — the AI turns it into structured
        nutrition. 1–512 characters.
      </p>
      <textarea
        className="text-input feedback-textarea"
        maxLength={512}
        value={text}
        autoFocus
        aria-label="What did you eat?"
        placeholder="e.g. Cheese toastie and a bowl of tomato soup"
        onChange={(e) => setText(e.target.value)}
      />
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-primary"
          disabled={!text.trim()}
          onClick={submit}
        >
          Log it
        </button>
      </div>
    </Modal>
  );
}

function EditValuesModal({
  slot,
  repair,
  onSubmit,
  onClose,
}: {
  slot: IntakeSlotDto;
  /** True when repairing a failed override parse (mock-only path). */
  repair: boolean;
  onSubmit: (values: {
    calories: number;
    proteinG: number;
    carbsG: number;
    fatG: number;
    fibreG: number | null;
    micros: Record<string, number>;
  }) => void;
  onClose: () => void;
}) {
  const p = slot.planned;
  const [cal, setCal] = useState(repair ? "" : String(p.calories ?? ""));
  const [protein, setProtein] = useState(repair ? "" : String(p.proteinG ?? ""));
  const [carbs, setCarbs] = useState(repair ? "" : String(p.carbsG ?? ""));
  const [fat, setFat] = useState(repair ? "" : String(p.fatG ?? ""));
  const [fibre, setFibre] = useState(repair ? "" : String(p.fibreG ?? ""));
  const [advanced, setAdvanced] = useState(false);
  // Micro rows start empty. The backend stores whatever this form sends as
  // measured actuals, so a planned micro the user never saw must not ride
  // along in the payload. Planned values render read-only in the advanced
  // section; "Use planned values" copies them into the editable rows.
  const [microRows, setMicroRows] = useState<MicroRow[]>([]);
  const [plannedCopied, setPlannedCopied] = useState(false);
  const plannedMicroRows = repair ? [] : rowsFromMicros(p.micros);

  const num = (v: string): number | null => {
    if (v.trim() === "") return null;
    const n = Number(v);
    return Number.isFinite(n) && n >= 0 ? n : null;
  };
  const required = [num(cal), num(protein), num(carbs), num(fat)];
  const valid = required.every((n) => n !== null);

  const field = (
    label: string,
    value: string,
    set: (v: string) => void,
    requiredField: boolean,
  ) => (
    <label style={{ display: "grid", gap: 4 }}>
      <span className="field-label">
        {label}
        {requiredField ? " *" : ""}
      </span>
      <input
        type="number"
        className="text-input num-input"
        value={value}
        min={0}
        onChange={(e) => set(e.target.value)}
        aria-label={label}
      />
    </label>
  );

  return (
    <Modal
      label={`Edit values — ${mealSlotLabel(slot.mealSlot)}`}
      onClose={onClose}
    >
      <span className="mp-label">
        Edit values · {mealSlotLabel(slot.mealSlot)}
      </span>
      {repair && (
        <p className="dialog-body">
          Entering values for an unparsed override — allowed in the mock for
          design review; the backend has no repair transition yet (backend gap
          — see spec §8).
        </p>
      )}
      <div
        style={{
          display: "flex",
          gap: 12,
          flexWrap: "wrap",
          marginTop: repair ? 0 : 14,
        }}
      >
        {field("kcal", cal, setCal, true)}
        {field("protein g", protein, setProtein, true)}
        {field("carbs g", carbs, setCarbs, true)}
        {field("fat g", fat, setFat, true)}
        {field("fibre g", fibre, setFibre, false)}
      </div>
      <div style={{ marginTop: 14 }}>
        <button className="btn btn-small" onClick={() => setAdvanced((a) => !a)}>
          {advanced ? "Hide micros" : "Advanced · micros"}
        </button>
        {advanced && (
          <div style={{ marginTop: 10 }}>
            {plannedMicroRows.length > 0 && !plannedCopied && (
              <div
                className="mp-card"
                data-testid="planned-micros-reference"
                style={{ padding: 10, marginBottom: 10 }}
              >
                <span className="mp-label" style={{ color: "var(--mp-muted)" }}>
                  Planned · not logged
                </span>
                <div className="inline-note" style={{ margin: "6px 0 8px" }}>
                  {plannedMicroRows
                    .map((r) => `${microLabel(r.key)} ${r.value}`)
                    .join(" · ")}
                </div>
                <button
                  className="btn btn-small"
                  onClick={() => {
                    setMicroRows(plannedMicroRows.map((r) => ({ ...r })));
                    setPlannedCopied(true);
                  }}
                >
                  Use planned values
                </button>
                <div className="inline-note" style={{ marginTop: 6 }}>
                  planned micros are saved only if you copy them in
                </div>
              </div>
            )}
            <MicroRowsEditor
              rows={microRows}
              onChange={setMicroRows}
              idPrefix="edit"
            />
          </div>
        )}
      </div>
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-primary"
          disabled={!valid}
          onClick={() => {
            onSubmit({
              calories: num(cal) ?? 0,
              proteinG: num(protein) ?? 0,
              carbsG: num(carbs) ?? 0,
              fatG: num(fat) ?? 0,
              fibreG: num(fibre),
              micros: microsFromRows(microRows),
            });
            onClose();
          }}
        >
          Save values
        </button>
      </div>
    </Modal>
  );
}

function SlotRow({
  date,
  slot,
  name,
  time,
  parsing,
}: {
  date: string;
  slot: IntakeSlotDto;
  name: string;
  time: string;
  parsing: boolean;
}) {
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const a = slot.actual;
  const p = slot.planned;

  const decidedWithValues =
    a.status !== "PENDING" && a.status !== "SKIPPED" && !a.needsAiParse;
  const deltaKcal =
    decidedWithValues && p.calories ? (a.calories ?? 0) - p.calories : 0;
  const showDelta =
    decidedWithValues &&
    !!p.calories &&
    Math.abs(deltaKcal / p.calories) > 0.1;

  return (
    <div className="slot-row2">
      <div>
        <span className="mp-num" style={{ fontSize: 19 }}>
          {time}
        </span>
        <div style={{ marginTop: 3 }}>
          <span className="mp-label">{mealSlotLabel(slot.mealSlot)}</span>
        </div>
      </div>

      <div style={{ minWidth: 0 }}>
        <span className="slot-name">{name}</span>
        <MacroLine
          calories={p.calories}
          proteinG={p.proteinG}
          carbsG={p.carbsG}
          fatG={p.fatG}
          fibreG={p.fibreG}
        />
      </div>

      <div style={{ minWidth: 0 }}>
        {parsing ? (
          <div className="intake-meta">Reading what you ate…</div>
        ) : a.status === "PENDING" ? (
          <div className="intake-meta">— not decided yet</div>
        ) : a.status === "SKIPPED" ? (
          <div className="intake-state skipped">— skipped · 0 kcal</div>
        ) : a.needsAiParse ? (
          <>
            <div className="override-quote">“{a.overrideFreeText}”</div>
            <div className="parse-banner" role="alert">
              <span>Couldn't read that — enter values manually</span>
              <button
                className="btn btn-small"
                onClick={() => setEditOpen(true)}
              >
                Enter values
              </button>
              <span className="inline-note">(backend gap — see spec §8)</span>
            </div>
          </>
        ) : (
          <>
            <div
              className="intake-state confirmed"
              style={{ display: "flex", gap: 8, alignItems: "baseline", flexWrap: "wrap" }}
            >
              <span>✓ {fmtKcal(a.calories ?? 0)} kcal logged</span>
              {showDelta && (
                <span className="delta-pill">
                  {deltaKcal > 0 ? "+" : "−"}
                  {fmtKcal(Math.abs(deltaKcal))} kcal vs plan
                </span>
              )}
            </div>
            <MacroLine
              proteinG={a.proteinG}
              carbsG={a.carbsG}
              fatG={a.fatG}
              fibreG={a.fibreG}
            />
            {a.status === "OVERRIDDEN" && a.overrideFreeText && (
              <div className="override-quote">
                logged: “{a.overrideFreeText}”
              </div>
            )}
          </>
        )}
      </div>

      <div className="actions-wrap">
        <SlotStateChip status={a.status} />
        {a.status === "PENDING" && !parsing && (
          <>
            <button
              className="btn btn-small btn-primary"
              onClick={() => confirmSlot(date, slot.mealSlot)}
            >
              Confirm
            </button>
            <button
              className="btn btn-small"
              onClick={() => setOverrideOpen(true)}
            >
              Log what I ate
            </button>
            <button className="btn btn-small" onClick={() => setEditOpen(true)}>
              Edit values
            </button>
            <button
              className="btn btn-small"
              onClick={() => skipSlot(date, slot.mealSlot)}
            >
              Skip
            </button>
          </>
        )}
      </div>

      {overrideOpen && (
        <OverrideModal
          mealSlot={slot.mealSlot}
          onSubmit={(text) => overrideSlot(date, slot.mealSlot, text)}
          onClose={() => setOverrideOpen(false)}
        />
      )}
      {editOpen && (
        <EditValuesModal
          slot={slot}
          repair={a.status === "OVERRIDDEN" && a.needsAiParse}
          onSubmit={(values) => editSlot(date, slot.mealSlot, values)}
          onClose={() => setEditOpen(false)}
        />
      )}
    </div>
  );
}

/* ---- snacks (spec §3e) ----------------------------------------------------------- */

interface SnackDraft {
  text: string;
  qty: string;
  cal: string;
  protein: string;
  carbs: string;
  fat: string;
  fibre: string;
  microRows: MicroRow[];
  mappingKey: string | null;
  source: IntakeSource;
  pieceHint: number | null;
}

const EMPTY_DRAFT: SnackDraft = {
  text: "",
  qty: "100",
  cal: "",
  protein: "",
  carbs: "",
  fat: "",
  fibre: "",
  microRows: [],
  mappingKey: null,
  source: "MANUAL",
  pieceHint: null,
};

function draftFromQuick(req: LogSnackRequest): SnackDraft {
  return {
    text: req.freeText,
    qty: String(req.quantityG),
    cal: String(req.calories),
    protein: String(req.proteinG),
    carbs: String(req.carbsG),
    fat: String(req.fatG),
    fibre: req.fibreG != null ? String(req.fibreG) : "",
    microRows: rowsFromMicros(req.micros),
    mappingKey: req.ingredientMappingKey ?? null,
    source: req.source,
    pieceHint: null,
  };
}

function SnacksSection({ date }: { date: string }) {
  const day = useStore((s) => s.nutrition.intakeDays[date]);
  const cache = useStore((s) => s.nutrition.ingredientCache);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<SnackDraft>(EMPTY_DRAFT);
  const [advanced, setAdvanced] = useState(false);
  const [suggestions, setSuggestions] = useState<IngredientNutritionDto[]>([]);
  const debounceRef = useRef<number | undefined>(undefined);

  const snacks = day?.snacks ?? [];
  const totalKcal = snacks.reduce((acc, sn) => acc + sn.calories, 0);

  const set = (patch: Partial<SnackDraft>) =>
    setDraft((d) => ({ ...d, ...patch }));

  /** Autofill macros = per-100 g × quantity / 100 (spec §3e). */
  const applyPer100 = (row: IngredientNutritionDto, grams: number) => {
    const d = row.nutritionPer100g;
    const f = grams / 100;
    const scaledMicros: Record<string, number> = {};
    for (const [k, v] of Object.entries(d.micros ?? {})) {
      scaledMicros[k] = Math.round(v * f * 100) / 100;
    }
    if (d.saturatedFatG != null) {
      scaledMicros["saturated_fat_g"] = Math.round(d.saturatedFatG * f * 10) / 10;
    }
    set({
      cal: String(Math.round((d.calories ?? 0) * f)),
      protein: fmtG((d.proteinG ?? 0) * f),
      carbs: fmtG((d.carbsG ?? 0) * f),
      fat: fmtG((d.fatG ?? 0) * f),
      fibre: d.fibreG != null ? fmtG(d.fibreG * f) : "",
      microRows: rowsFromMicros(scaledMicros),
    });
  };

  const onText = (v: string) => {
    set({ text: v, mappingKey: null, source: "MANUAL", pieceHint: null });
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => {
      setSuggestions(searchIngredients(cache, v, 5));
    }, 250);
  };

  const pick = (row: IngredientNutritionDto) => {
    const grams = row.defaultPieceGrams ?? (Number(draft.qty) || 100);
    setDraft((d) => ({
      ...d,
      text: row.searchTerm,
      mappingKey: row.searchTerm,
      source: row.source,
      pieceHint: row.defaultPieceGrams ?? null,
      qty: String(grams),
    }));
    applyPer100(row, grams);
    setSuggestions([]);
  };

  const onQty = (v: string) => {
    set({ qty: v });
    const grams = Number(v);
    if (draft.mappingKey && Number.isFinite(grams) && grams > 0) {
      const row = cache.find((r) => r.searchTerm === draft.mappingKey);
      if (row) applyPer100(row, grams);
    }
  };

  const num = (v: string): number | null => {
    if (v.trim() === "") return null;
    const n = Number(v);
    return Number.isFinite(n) && n >= 0 ? n : null;
  };
  const valid =
    draft.text.trim() !== "" &&
    (num(draft.qty) ?? 0) > 0 &&
    [draft.cal, draft.protein, draft.carbs, draft.fat].every(
      (v) => num(v) !== null,
    );

  const submit = () => {
    addSnack(date, {
      freeText: draft.text,
      ingredientMappingKey: draft.mappingKey,
      quantityG: num(draft.qty) ?? 0,
      calories: num(draft.cal) ?? 0,
      proteinG: num(draft.protein) ?? 0,
      carbsG: num(draft.carbs) ?? 0,
      fatG: num(draft.fat) ?? 0,
      fibreG: num(draft.fibre),
      micros: microsFromRows(draft.microRows),
      source: draft.source,
      deductFromPantry: false,
    });
    setDraft(EMPTY_DRAFT);
    setAdvanced(false);
    setOpen(false);
  };

  const numField = (
    label: string,
    value: string,
    onChange: (v: string) => void,
    requiredField: boolean,
  ) => (
    <label style={{ display: "grid", gap: 4 }}>
      <span className="field-label">
        {label}
        {requiredField ? " *" : ""}
      </span>
      <input
        type="number"
        className="text-input num-input"
        min={0}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
      />
    </label>
  );

  return (
    <div style={{ marginTop: 26 }}>
      <div className="group-head">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Snacks · {fmtKcal(totalKcal)} kcal
        </span>
      </div>
      {snacks.length === 0 ? (
        <div className="intake-meta" style={{ padding: "10px 0" }}>
          Nothing logged yet.
        </div>
      ) : (
        snacks.map((sn) => (
          <div key={sn.id} className="snack-row">
            <div style={{ minWidth: 0 }}>
              <span>{sn.freeText}</span>
              <div className="macro-line">
                {fmtG(sn.quantityG)} g · P {fmtG(sn.proteinG)} · C{" "}
                {fmtG(sn.carbsG)} · F {fmtG(sn.fatG)}
                {sn.fibreG != null ? ` · Fb ${fmtG(sn.fibreG)}` : ""}
              </div>
            </div>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 10,
                flexShrink: 0,
              }}
            >
              <span className="snack-kcal">{fmtKcal(sn.calories)} kcal</span>
              <SourceBadge source={sn.source} />
              <span className="inline-note">{shortTime(sn.loggedAt)}</span>
              <button
                className="chip-x"
                aria-label={`Remove ${sn.freeText}`}
                onClick={() => removeSnack(date, sn.id)}
              >
                ✕
              </button>
            </div>
          </div>
        ))
      )}

      {!open ? (
        <button
          className="btn"
          style={{ marginTop: 14 }}
          onClick={() => setOpen(true)}
        >
          + Log a snack
        </button>
      ) : (
        <div className="mp-card side-card" style={{ marginTop: 14 }}>
          <span className="mp-label">Log a snack</span>
          <div className="snack-chips" style={{ marginTop: 10 }}>
            {QUICK_SNACKS.map((q) => (
              <button
                key={q.label}
                className="filter-chip"
                onClick={() => setDraft(draftFromQuick(q.req))}
              >
                {q.label}
              </button>
            ))}
          </div>
          <div className="suggest-anchor" style={{ marginTop: 12 }}>
            <input
              type="text"
              className="text-input"
              style={{ width: "100%" }}
              placeholder="What was it? Typing searches your ingredient cache…"
              maxLength={255}
              value={draft.text}
              onChange={(e) => onText(e.target.value)}
              aria-label="Snack free text"
            />
            {suggestions.length > 0 && (
              <div className="suggest-pop mp-card">
                {suggestions.map((row) => (
                  <button
                    key={row.searchTerm}
                    className="suggest-row"
                    onClick={() => pick(row)}
                  >
                    <span>{row.searchTerm}</span>
                    <span className="inline-note">
                      {fmtKcal(row.nutritionPer100g.calories ?? 0)} kcal / 100 g
                      · {row.source === "OPEN_FOOD_FACTS" ? "OFF" : row.source}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <div
            style={{
              display: "flex",
              gap: 12,
              flexWrap: "wrap",
              marginTop: 12,
              alignItems: "end",
            }}
          >
            <label style={{ display: "grid", gap: 4 }}>
              <span className="field-label">quantity g *</span>
              <input
                type="number"
                className="text-input num-input"
                min={1}
                value={draft.qty}
                onChange={(e) => onQty(e.target.value)}
                aria-label="Quantity in grams"
              />
            </label>
            {numField("kcal", draft.cal, (v) => set({ cal: v }), true)}
            {numField("protein g", draft.protein, (v) => set({ protein: v }), true)}
            {numField("carbs g", draft.carbs, (v) => set({ carbs: v }), true)}
            {numField("fat g", draft.fat, (v) => set({ fat: v }), true)}
            {numField("fibre g", draft.fibre, (v) => set({ fibre: v }), false)}
          </div>
          {draft.pieceHint != null && (
            <div className="inline-note" style={{ marginTop: 6 }}>
              1 piece ≈ {draft.pieceHint} g
            </div>
          )}
          <div
            style={{
              display: "flex",
              gap: 14,
              alignItems: "center",
              marginTop: 12,
              flexWrap: "wrap",
            }}
          >
            <button
              className="btn btn-small"
              onClick={() => setAdvanced((a) => !a)}
            >
              {advanced ? "Hide micros" : "Advanced · micros"}
            </button>
            <span
              style={{ display: "inline-flex", gap: 8, alignItems: "center" }}
              title="coming in v1.5"
            >
              <Switch
                on={false}
                disabled
                label="Deduct from pantry"
                title="coming in v1.5"
              />
              <span className="inline-note">deduct from pantry</span>
            </span>
            <span style={{ flex: 1 }} />
            <span className="inline-note">
              source: {draft.source === "OPEN_FOOD_FACTS" ? "OFF" : draft.source.toLowerCase()}
            </span>
          </div>
          {advanced && (
            <div style={{ marginTop: 10 }}>
              <MicroRowsEditor
                rows={draft.microRows}
                onChange={(rows) => set({ microRows: rows })}
                idPrefix="snack"
              />
            </div>
          )}
          <div className="modal-actions">
            <button
              className="btn"
              onClick={() => {
                setDraft(EMPTY_DRAFT);
                setOpen(false);
              }}
            >
              Cancel
            </button>
            <button className="btn btn-primary" disabled={!valid} onClick={submit}>
              Log snack
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/* ---- journal (spec §3f) ------------------------------------------------------------ */

function SlotSelect({
  value,
  onChange,
  label,
}: {
  value: string;
  onChange: (v: string) => void;
  label: string;
}) {
  return (
    <select
      className="time-select"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      aria-label={label}
    >
      <option value="">whole day</option>
      <option value="BREAKFAST">breakfast</option>
      <option value="LUNCH">lunch</option>
      <option value="DINNER">dinner</option>
      <option value="SNACKS">snacks</option>
    </select>
  );
}

function JournalRow({ entry }: { entry: FoodMoodEntryDto }) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(entry.journalEntry);
  const [slotSel, setSlotSel] = useState<string>(entry.mealSlot ?? "");

  const save = () => {
    updateJournalEntry(
      entry.id,
      text,
      slotSel === "" ? null : (slotSel as MealSlot),
    );
    setEditing(false);
  };

  return (
    <div className="journal-row">
      <div className="journal-meta">
        <span>
          {shortDate(entry.onDate)} · {shortTime(entry.loggedAt)}
        </span>
        {entry.mealSlot && (
          <span className="tint-chip olive">
            {mealSlotLabel(entry.mealSlot)}
          </span>
        )}
        <span style={{ flex: 1 }} />
        {!editing && (
          <>
            <button
              className="btn btn-small"
              onClick={() => {
                setText(entry.journalEntry);
                setSlotSel(entry.mealSlot ?? "");
                setEditing(true);
              }}
            >
              Edit
            </button>
            <button
              className="btn btn-small"
              onClick={() => deleteJournalEntry(entry.id)}
            >
              Delete
            </button>
          </>
        )}
      </div>
      {editing ? (
        <div style={{ marginTop: 8, display: "grid", gap: 8 }}>
          <textarea
            className="text-input"
            style={{ width: "100%", minHeight: 60, resize: "vertical" }}
            maxLength={4000}
            value={text}
            onChange={(e) => setText(e.target.value)}
            aria-label="Edit journal entry"
          />
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <SlotSelect
              value={slotSel}
              onChange={setSlotSel}
              label="Meal slot for entry"
            />
            <span style={{ flex: 1 }} />
            <button className="btn btn-small" onClick={() => setEditing(false)}>
              Cancel
            </button>
            <button
              className="btn btn-small btn-primary"
              disabled={!text.trim()}
              onClick={save}
            >
              Save
            </button>
          </div>
        </div>
      ) : (
        <div className="journal-text">{entry.journalEntry}</div>
      )}
    </div>
  );
}

function JournalSection({ date }: { date: string }) {
  const journal = useStore((s) => s.nutrition.journal);
  const [showEarlier, setShowEarlier] = useState(false);
  const [text, setText] = useState("");
  const [slotSel, setSlotSel] = useState<string>("");

  const dayEntries = journal.filter((e) => e.onDate === date);
  const earlier = journal.filter((e) => e.onDate !== date);

  const add = () => {
    addJournalEntry(date, slotSel === "" ? null : (slotSel as MealSlot), text);
    setText("");
    setSlotSel("");
  };

  return (
    <div className="mp-card side-card">
      <span className="mp-label">Food & mood journal</span>
      <div style={{ marginTop: 10 }}>
        {dayEntries.length === 0 ? (
          <div className="intake-meta">No entries for this day.</div>
        ) : (
          dayEntries.map((e) => <JournalRow key={e.id} entry={e} />)
        )}
      </div>
      <div style={{ display: "grid", gap: 8, marginTop: 12 }}>
        <textarea
          className="text-input"
          style={{ width: "100%", minHeight: 54, resize: "vertical" }}
          placeholder="Add a note — how did food feel today?"
          maxLength={4000}
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-label="Add journal note"
        />
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <SlotSelect
            value={slotSel}
            onChange={setSlotSel}
            label="Meal slot for new entry"
          />
          <span style={{ flex: 1 }} />
          <button className="btn btn-small" disabled={!text.trim()} onClick={add}>
            Add
          </button>
        </div>
      </div>
      {earlier.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <button
            className="btn btn-small"
            onClick={() => setShowEarlier((v) => !v)}
          >
            {showEarlier
              ? "Hide earlier entries"
              : `Earlier entries · ${earlier.length}`}
          </button>
          {showEarlier && (
            <div style={{ marginTop: 6 }}>
              {earlier.map((e) => (
                <JournalRow key={e.id} entry={e} />
              ))}
              <div className="inline-note" style={{ marginTop: 6 }}>
                page 1 of 1 — the mock holds the full history
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ---- micros panel (spec §3g, collapsed by default) ------------------------------------ */

function MicrosPanel({
  agg,
  targets,
}: {
  agg: DailyAggregateDto;
  targets: TargetsDto;
}) {
  // Status rows are the source of truth (DailyAggregateDto.micros): NO_DATA
  // means no decided slot or snack carried the key — unmeasured, never zero
  // (t5 B5). Rendered inline in nutrient order (FC5): target keys first, then
  // measured untargeted keys, same order as the projection panel's grammar.
  // Sat fat lives in the stat band, not here.
  const byKey = new Map(agg.micros.map((m) => [m.key, m]));
  const keys = [
    ...targets.microTargets.map((t) => t.nutrientKey),
    ...agg.micros
      .map((m) => m.key)
      .filter((k) => !targets.microTargets.some((t) => t.nutrientKey === k)),
  ].filter((k) => k !== "saturated_fat_g" && byKey.has(k));

  return (
    <details className="mp-card micros-details">
      <summary>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Micronutrients · {keys.length} tracked
        </span>
        <span className="inline-note">
          shown in v1 by product-owner override (spec §8)
        </span>
      </summary>
      {keys.map((key) => {
        const mt = targets.microTargets.find((t) => t.nutrientKey === key);
        const row = byKey.get(key);
        // Null value = NO_DATA: muted row, target kept, empty bar, never a
        // zero and never the warn treatment (the row is unmeasured, not
        // short — same exclusion the projection lens applies).
        const actual = row?.status === "NO_DATA" ? null : (row?.actualSoFar ?? null);
        const over =
          actual != null && mt?.upperLimit != null && actual > mt.upperLimit;
        const tooltip =
          [mt?.notes, mt?.sourcePreference].filter(Boolean).join(" · ") ||
          undefined;
        // Shared row grammar with the Plan page's projection panel. The
        // retrospective warn is upper-limit exceedance, data the target
        // already carries.
        return (
          <NutrientRow
            key={key}
            label={microLabel(key)}
            unit={microUnit(key)}
            target={mt ? (mt.targetValue ?? mt.upperLimit ?? null) : null}
            upperBound={mt != null && mt.targetValue == null && mt.upperLimit != null}
            value={actual}
            warn={over}
            warnTitle="over the upper limit"
            hardFloor={mt?.isHardFloor ?? false}
            tooltip={tooltip}
          />
        );
      })}
      <div className="inline-note" style={{ marginTop: 8 }}>
        "no data" means no logged food carried the nutrient — unmeasured, not
        zero.
      </div>
    </details>
  );
}

/* ---- the tab --------------------------------------------------------------------------- */

export function OverviewTab() {
  const nutrition = useStore((s) => s.nutrition);
  const targets = useStore((s) => s.targets);
  const recipes = useStore((s) => s.recipes);
  const activePlan = useStore((s) => activePlanForWeek(s, CURRENT_WEEK_START));
  const slotTimes = useStore(selectSlotTimes);
  const navigate = useNavigate();

  const [dayIdx, setDayIdx] = useState(TODAY_INDEX);
  const date = WEEK_DATES[dayIdx];
  const day = nutrition.intakeDays[date];

  // Targets 404: initialise CTA as the empty state, never an error (§8).
  if (!targets) {
    return (
      <div style={{ marginTop: 18 }}>
        <TargetsEmptyState />
      </div>
    );
  }

  const agg = computeDailyAggregate(day, targets);
  // Live-aware anchors so the aggregate lines up with the real week.
  const week = computeWeeklyAggregate(nutrition, targets, WEEK_DATES, MOCK_TODAY_ISO);
  // Chips prefer the backend's weekly aggregate when hydrated (live mode);
  // the mock computes the contract-equivalent shape.
  const floorViolations: FloorViolationDto[] =
    nutrition.weeklyAggregate?.floorViolations ?? week.floorViolations;
  const divergence =
    date === MOCK_TODAY_ISO ? computeDivergence(day) : null;
  const pendingCount =
    day?.slots.filter((sl) => sl.actual.status === "PENDING").length ?? 0;

  const planDay = activePlan?.days.find((d) => d.date === date);

  const plannedName = (slot: IntakeSlotDto): string => {
    if (slot.planned.recipeId) {
      const r = recipes.find((x) => x.id === slot.planned.recipeId);
      if (r) return r.name;
    }
    // Planner kind ↔ nutrition mealSlot join (SNACKS has no planner column).
    const planSlot = planDay?.slots.find((sl) => sl.kind === slot.mealSlot);
    return planSlot?.scheduledRecipe
      ? recipeName(recipes, planSlot.scheduledRecipe.recipeId)
      : "—";
  };

  const slotTime = (slot: IntakeSlotDto): string => {
    const key = slot.mealSlot.toLowerCase() as MealSlotKey;
    return slotTimes[key] ?? "";
  };

  return (
    <div>
      {/* Day header (spec §3a) */}
      <div className="day-header">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <button
            className="stepper-btn"
            aria-label="Previous day"
            disabled={dayIdx === 0}
            onClick={() => setDayIdx((i) => i - 1)}
          >
            ‹
          </button>
          <span className="day-title">
            {prettyDate(date)}
            {date === MOCK_TODAY_ISO && (
              <span className="plan-today-tag"> · TODAY</span>
            )}
          </span>
          <button
            className="stepper-btn"
            aria-label="Next day"
            disabled={dayIdx === WEEK_DATES.length - 1}
            onClick={() => setDayIdx((i) => i + 1)}
          >
            ›
          </button>
        </div>
        <ActivityControl date={date} />
      </div>

      {/* Divergence advisor (spec §3a) */}
      {divergence && pendingCount > 0 && (
        <AdvisorCard
          label="Advisor · divergence"
          title="Today has drifted from plan — re-optimise the rest of the week?"
          sub={`${divergence.key} is ${divergence.pct > 0 ? "+" : ""}${Math.round(
            divergence.pct,
          )}% vs the plan so far, with ${pendingCount} slot${
            pendingCount === 1 ? "" : "s"
          } still pending`}
          actions={
            <button className="btn btn-primary" onClick={() => navigate("/plan")}>
              Re-optimise
            </button>
          }
        />
      )}

      {/* Six-cell stat band (spec §3b) */}
      <StatBandSix agg={agg} targets={targets} />
      <div className="inline-note" style={{ marginTop: 6 }}>
        Sat fat reads micros.saturated_fat_g — DailyAggregateDto has no satFat
        aggregate (backend gap, flagged on the spec PR).
      </div>

      {/* Week strip (spec §3c) */}
      <div className="week-strip mp-card" aria-label="This week">
        {week.perDay.map((d, i) => {
          const isToday = i === TODAY_INDEX;
          const isSelected = i === dayIdx;
          const act = nutrition.dailyActivity[WEEK_DATES[i]];
          return (
            <button
              key={WEEK_DATES[i]}
              className={`week-cell${isToday ? " today" : ""}`}
              onClick={() => setDayIdx(i)}
              title={`View ${WEEK_DAY_LABELS[i]}`}
              style={{
                background: "transparent",
                border: "none",
                cursor: "pointer",
                textAlign: "left",
                font: "inherit",
                color: "inherit",
                padding: 0,
                ...(isToday ? { paddingLeft: 10 } : {}),
              }}
            >
              <span
                className="mp-label"
                style={
                  isToday
                    ? { color: "var(--mp-terra)" }
                    : isSelected
                      ? { color: "var(--mp-ink)" }
                      : undefined
                }
              >
                {WEEK_DAY_LABELS[i]}
              </span>
              <div className="week-kcal">
                {d.caloriesActualSoFar > 0 ? (
                  <span className="mp-num" style={{ fontSize: 15 }}>
                    {fmtKcal(d.caloriesActualSoFar)}
                  </span>
                ) : (
                  <span style={{ color: "var(--mp-muted)", fontSize: 12 }}>
                    —
                  </span>
                )}
              </div>
              <SegmentBar
                pct={
                  targets.calories.dailyTarget > 0
                    ? d.caloriesActualSoFar / targets.calories.dailyTarget
                    : 0
                }
                segments={12}
              />
              {act && (
                <span
                  className="week-activity"
                  title={`${ACTIVITY_LABEL[act.activityLevel]}${
                    act.notes ? ` — ${act.notes}` : ""
                  }`}
                >
                  {ACTIVITY_BADGE[act.activityLevel]}
                </span>
              )}
            </button>
          );
        })}
        <div className="week-cell week-total-cell">
          <span className="mp-label">Week</span>
          <div className="week-kcal">
            <span className="mp-num" style={{ fontSize: 15 }}>
              {fmtKcal(week.weeklyTotal.caloriesActualSoFar)}
            </span>
          </div>
          <span className="inline-note">
            / {fmtKcal(targets.calories.dailyTarget * 7)} kcal
          </span>
        </div>
      </div>
      {floorViolations.length > 0 && (
        <div className="violation-chips">
          {/* One chip per FloorViolationDto: dated entries name the day,
              date:null entries are weekly-average floors ("this week"). */}
          {floorViolations.map((v) => {
            const dayIdxOf = v.date ? WEEK_DATES.indexOf(v.date) : -1;
            const when =
              v.date == null
                ? "this week"
                : dayIdxOf >= 0
                  ? WEEK_DAY_LABELS[dayIdxOf]
                  : shortDate(v.date);
            return (
              <span
                key={`${v.macroOrMicro}-${v.date ?? "week"}`}
                className="tint-chip red"
                title={`${fmtG(v.actual)} vs floor ${fmtG(v.floor)}`}
              >
                {v.macroOrMicro} floor missed · {when}
              </span>
            );
          })}
        </div>
      )}

      <div className="nutrition-layout">
        <div>
          {/* Slots (spec §3d) — scrolls horizontally on narrow windows */}
          <div className="slots-scroll">
            <div className="slot-grid-head">
              <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
                Slots
              </span>
              <span className="mp-label">Planned</span>
              <span className="mp-label">Actual</span>
              <span className="mp-label" style={{ textAlign: "right" }}>
                State
              </span>
            </div>
            {day ? (
              day.slots.map((slot) => (
                <SlotRow
                  key={slot.id}
                  date={date}
                  slot={slot}
                  name={plannedName(slot)}
                  time={slotTime(slot)}
                  parsing={nutrition.parsingSlotIds.includes(slot.id)}
                />
              ))
            ) : (
              <div className="intake-meta" style={{ padding: "12px 0" }}>
                No plan covered this day — generate a plan to start logging.
              </div>
            )}
          </div>

          <SnacksSection date={date} />
        </div>

        <div style={{ display: "grid", gap: 18, alignContent: "start" }}>
          <JournalSection date={date} />
          <MicrosPanel agg={agg} targets={targets} />
        </div>
      </div>
    </div>
  );
}
