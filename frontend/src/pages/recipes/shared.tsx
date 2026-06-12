/**
 * Shared bits for the recipes library, recipe detail and discover pages:
 * quality badges, nutrition-status captions, the §4d recipe form (manual
 * create / import review / edit / branch body — one component, recipes.md
 * §4d), and the structured-diff renderer (recipe-detail.md §5c).
 */

import { useMemo, useState } from "react";
import type { ReactNode } from "react";
import { TintChip } from "../../components/TintChip";
import type {
  CreateIngredientRequest,
  CreateRecipeRequest,
  DataQuality,
  IngredientDto,
  NutritionStatus,
  RecipeDiffDto,
  RecipeDto,
} from "../../mock/types";

/* ---- formatting ----------------------------------------------------------------- */

/** "2026-06-03T07:55:00Z" → "Wed 3 Jun". */
export function shortWhen(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}

export function qtyStr(
  quantity: number | null | undefined,
  unit: string | null | undefined,
): string {
  if (quantity == null) return "to taste";
  const q = Number.isInteger(quantity) ? String(quantity) : String(quantity);
  return unit ? `${q} ${unit}` : q;
}

/** Card meta line from the hydrated body (recipes.md §3a). */
export function metaLine(recipe: RecipeDto): string {
  const m = recipe.currentVersionBody?.metadata;
  if (!m) return "—";
  const parts = [`${m.totalTimeMins} min`, `serves ${m.servings}`];
  if (m.cuisine) parts.push(m.cuisine);
  return parts.join(" · ");
}

/* ---- quality + nutrition badges --------------------------------------------------- */

/** Ordinal trust ranking for the minDataQuality floor filter (§3b). */
export const QUALITY_ORDER: DataQuality[] = [
  "WEB_DISCOVERED",
  "AI_GENERATED",
  "IMPORTED",
  "USER_VERIFIED",
];

export const QUALITY_LABEL: Record<DataQuality, string> = {
  USER_VERIFIED: "User verified",
  IMPORTED: "imported",
  AI_GENERATED: "ai generated",
  WEB_DISCOVERED: "web discovered",
};

export function QualityBadge({ quality }: { quality: DataQuality }) {
  if (quality === "USER_VERIFIED") return <TintChip>User verified</TintChip>;
  return <span className="tier-badge">{QUALITY_LABEL[quality]}</span>;
}

export function needsReviewCountOf(recipe: RecipeDto): number {
  return (
    recipe.currentVersionBody?.ingredients.filter((i) => i.needsReview).length ?? 0
  );
}

/** PENDING / PARTIAL captions (CALCULATED renders nothing — §3a). */
export function NutritionStatusNote({
  status,
  needsReview,
}: {
  status: NutritionStatus;
  needsReview: number;
}) {
  if (status === "PENDING") {
    return <span className="nutri-note pending">nutrition pending</span>;
  }
  if (status === "PARTIAL") {
    return (
      <span className="nutri-note partial">
        {needsReview > 0
          ? `${needsReview} ingredient${needsReview === 1 ? "" : "s"} need review`
          : "nutrition partial"}
      </span>
    );
  }
  return null;
}

export function fmtIngredient(i: IngredientDto): string {
  let out = `${qtyStr(i.quantity, i.unit)} ${i.displayName}`;
  if (i.preparation) out += `, ${i.preparation}`;
  return out;
}

/* ---- structured diff renderer (recipe-detail.md §5c) ------------------------------- */

type Snapshot = NonNullable<RecipeDiffDto["ingredientChanges"][number]["from"]>;

function snapLine(s: Snapshot | null | undefined): string {
  if (!s) return "—";
  return `${qtyStr(s.quantity, s.unit)} ${s.displayName ?? s.ingredientMappingKey ?? ""}`;
}

function ActionMark({ action }: { action: "ADDED" | "REMOVED" | "MODIFIED" }) {
  const map = {
    ADDED: { glyph: "+", className: "added" },
    REMOVED: { glyph: "−", className: "removed" },
    MODIFIED: { glyph: "~", className: "modified" },
  } as const;
  const m = map[action];
  return (
    <span className={`diff-mark ${m.className}`} aria-label={action.toLowerCase()}>
      {m.glyph}
    </span>
  );
}

const fmtJson = (v: unknown): string =>
  v == null ? "—" : typeof v === "string" ? v : JSON.stringify(v);

export function DiffView({ diff }: { diff: RecipeDiffDto }) {
  const empty =
    diff.ingredientChanges.length === 0 &&
    diff.methodChanges.length === 0 &&
    diff.metadataChanges.length === 0 &&
    diff.tagChanges.length === 0;
  if (empty) {
    return <div className="inline-note">No structural changes recorded.</div>;
  }
  return (
    <div className="diff-view">
      {diff.ingredientChanges.map((ch, i) => (
        <div key={`ing-${i}`} className="diff-row">
          <ActionMark action={ch.action} />
          {ch.action === "MODIFIED" && ch.fieldChanged ? (
            <span>
              {ch.to?.displayName ?? ch.from?.displayName} — {ch.fieldChanged}:{" "}
              <span className="diff-from">
                {fmtJson(ch.from?.[ch.fieldChanged as keyof Snapshot] ?? null)}
              </span>{" "}
              → <strong>{fmtJson(ch.to?.[ch.fieldChanged as keyof Snapshot] ?? null)}</strong>
            </span>
          ) : ch.action === "ADDED" ? (
            <span>{snapLine(ch.to)}</span>
          ) : (
            <span className="diff-from">{snapLine(ch.from)}</span>
          )}
        </div>
      ))}
      {diff.methodChanges.map((ch, i) => (
        <div key={`m-${i}`} className="diff-row">
          <ActionMark action={ch.action} />
          <span>
            step {ch.step}:{" "}
            {ch.action === "MODIFIED" ? (
              <>
                <span className="diff-from">{ch.from}</span> →{" "}
                <strong>{ch.to}</strong>
              </>
            ) : (
              (ch.to ?? ch.from)
            )}
          </span>
        </div>
      ))}
      {diff.metadataChanges.map((ch, i) => (
        <div key={`md-${i}`} className="diff-row">
          <ActionMark action={ch.action} />
          <span>
            {ch.field}: <span className="diff-from">{fmtJson(ch.from)}</span> →{" "}
            <strong>{fmtJson(ch.to)}</strong>
          </span>
        </div>
      ))}
      {diff.tagChanges.map((ch, i) => (
        <div key={`t-${i}`} className="diff-row">
          <ActionMark action={ch.action} />
          <span>
            {ch.dimension}: <span className="diff-from">{fmtJson(ch.from)}</span>{" "}
            → <strong>{fmtJson(ch.to)}</strong>
          </span>
        </div>
      ))}
    </div>
  );
}

/* ---- the §4d recipe form ------------------------------------------------------------ */

interface IngRow {
  displayName: string;
  mappingKey: string;
  keyTouched: boolean;
  quantity: string;
  unit: string;
  preparation: string;
  optional: boolean;
}

interface StepRow {
  instruction: string;
  durationMinutes: string;
}

function rowsFromRequest(req: CreateRecipeRequest): IngRow[] {
  return req.ingredients.map((i) => ({
    displayName: i.displayName,
    mappingKey: i.ingredientMappingKey,
    keyTouched: i.ingredientMappingKey !== deriveKey(i.displayName),
    quantity: i.quantity == null ? "" : String(i.quantity),
    unit: i.unit ?? "",
    preparation: i.preparation ?? "",
    optional: i.optional ?? false,
  }));
}

/** Mapping key auto-derived from the display name (advanced-editable). */
function deriveKey(displayName: string): string {
  return displayName.trim().toLowerCase().replace(/\s+/g, " ").slice(0, 160);
}

const num = (v: string): number | null => {
  if (v.trim() === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

export function RecipeForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
  extra,
  extraValid = true,
}: {
  initial: CreateRecipeRequest;
  submitLabel: string;
  onSubmit: (req: CreateRecipeRequest) => void;
  onCancel: () => void;
  /** Extra controls above the actions (e.g. the edit form's change note). */
  extra?: ReactNode;
  extraValid?: boolean;
}) {
  const [name, setName] = useState(initial.name);
  const [description, setDescription] = useState(initial.description ?? "");
  const [ings, setIngs] = useState<IngRow[]>(rowsFromRequest(initial));
  const [steps, setSteps] = useState<StepRow[]>(
    initial.method.map((m) => ({
      instruction: m.instruction,
      durationMinutes: m.durationMinutes == null ? "" : String(m.durationMinutes),
    })),
  );
  const meta = initial.metadata;
  const [servings, setServings] = useState(String(meta.servings));
  const [prepMins, setPrepMins] = useState(String(meta.prepTimeMins));
  const [cookMins, setCookMins] = useState(String(meta.cookTimeMins));
  const [totalMins, setTotalMins] = useState(String(meta.totalTimeMins));
  const [cuisine, setCuisine] = useState(meta.cuisine ?? "");
  const [equipment, setEquipment] = useState((meta.equipmentRequired ?? []).join(", "));
  const [mealTypes, setMealTypes] = useState((meta.mealTypes ?? []).join(", "));
  const [fridgeDays, setFridgeDays] = useState(
    meta.fridgeDays == null ? "" : String(meta.fridgeDays),
  );
  const [freezerWeeks, setFreezerWeeks] = useState(
    meta.freezerWeeks == null ? "" : String(meta.freezerWeeks),
  );
  const [packable, setPackable] = useState(meta.packable ?? false);
  const [showKeys, setShowKeys] = useState(false);
  const tags = initial.tags;
  const [protein, setProtein] = useState(tags?.protein ?? "");
  const [cookingMethod, setCookingMethod] = useState(tags?.cookingMethod ?? "");
  const [complexity, setComplexity] = useState<string>(tags?.complexity ?? "");
  const [flavour, setFlavour] = useState((tags?.flavourProfile ?? []).join(", "));
  const [dietary, setDietary] = useState((tags?.dietaryFlags ?? []).join(", "));
  const [tried, setTried] = useState(false);

  const errors = useMemo((): string[] => {
    const out: string[] = [];
    if (!name.trim() || name.trim().length > 160) out.push("Name is required (1–160).");
    const live = ings.filter((r) => r.displayName.trim() !== "");
    if (live.length === 0) out.push("At least one ingredient is required.");
    const pairs = new Set<string>();
    for (const r of live) {
      const key = `${(r.keyTouched ? r.mappingKey : deriveKey(r.displayName)).toLowerCase()}|${r.preparation.trim().toLowerCase()}`;
      if (pairs.has(key)) {
        out.push(`Duplicate ingredient (key, preparation): ${r.displayName}.`);
        break;
      }
      pairs.add(key);
    }
    if (steps.filter((s) => s.instruction.trim() !== "").length === 0) {
      out.push("At least one method step is required.");
    }
    const sv = num(servings);
    if (sv == null || sv < 1) out.push("Servings must be ≥ 1.");
    const p = num(prepMins);
    const c = num(cookMins);
    const t = num(totalMins);
    if (p == null || p < 0 || c == null || c < 0 || t == null || t < 0) {
      out.push("Prep, cook and total times must be ≥ 0.");
    } else if (Math.abs(t - (p + c)) > 5) {
      out.push(`Total time (${t}) must be within ±5 min of prep + cook (${p + c}).`);
    }
    if (fridgeDays.trim() === "" && freezerWeeks.trim() !== "") {
      out.push("Freezer weeks needs fridge days set too.");
    }
    return out;
  }, [name, ings, steps, servings, prepMins, cookMins, totalMins, fridgeDays, freezerWeeks]);

  const list = (v: string): string[] =>
    v.split(",").map((x) => x.trim()).filter(Boolean);

  const buildRequest = (): CreateRecipeRequest => {
    const live = ings.filter((r) => r.displayName.trim() !== "");
    const ingredients: CreateIngredientRequest[] = live.map((r, i) => ({
      lineOrder: i,
      ingredientMappingKey: r.keyTouched && r.mappingKey.trim() !== ""
        ? r.mappingKey.trim()
        : deriveKey(r.displayName),
      displayName: r.displayName.trim(),
      quantity: num(r.quantity),
      unit: r.unit.trim() === "" ? null : r.unit.trim().slice(0, 16),
      preparation: r.preparation.trim() === "" ? null : r.preparation.trim().slice(0, 80),
      optional: r.optional,
    }));
    const hasTags =
      protein.trim() || cookingMethod.trim() || complexity || flavour.trim() || dietary.trim();
    return {
      name: name.trim(),
      description: description.trim() === "" ? null : description.trim().slice(0, 2000),
      ingredients,
      method: steps
        .filter((s) => s.instruction.trim() !== "")
        .map((s, i) => ({
          stepNumber: i + 1, // contiguous from 1
          instruction: s.instruction.trim(),
          durationMinutes: num(s.durationMinutes),
        })),
      metadata: {
        servings: num(servings) ?? 1,
        prepTimeMins: num(prepMins) ?? 0,
        cookTimeMins: num(cookMins) ?? 0,
        totalTimeMins: num(totalMins) ?? 0,
        equipmentRequired: list(equipment),
        fridgeDays: num(fridgeDays),
        freezerWeeks: num(freezerWeeks),
        packable,
        cuisine: cuisine.trim() === "" ? null : cuisine.trim().slice(0, 64),
        mealTypes: list(mealTypes),
      },
      tags: hasTags
        ? {
            protein: protein.trim() || null,
            cookingMethod: cookingMethod.trim() || null,
            complexity:
              complexity === "MINIMAL" || complexity === "MODERATE" || complexity === "INVOLVED"
                ? complexity
                : null,
            flavourProfile: list(flavour),
            dietaryFlags: list(dietary),
          }
        : null,
    };
  };

  const submit = () => {
    setTried(true);
    if (errors.length > 0 || !extraValid) return;
    onSubmit(buildRequest());
  };

  const setIng = (i: number, patch: Partial<IngRow>) =>
    setIngs((xs) => xs.map((x, xi) => (xi === i ? { ...x, ...patch } : x)));

  return (
    <div className="recipe-form">
      <div className="rf-grid2">
        <div>
          <span className="field-label">Name *</span>
          <input
            type="text"
            className="text-input"
            value={name}
            maxLength={160}
            aria-label="Recipe name"
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div>
          <span className="field-label">Cuisine</span>
          <input
            type="text"
            className="text-input"
            value={cuisine}
            maxLength={64}
            aria-label="Cuisine"
            onChange={(e) => setCuisine(e.target.value)}
          />
        </div>
      </div>
      <div>
        <span className="field-label">Description</span>
        <textarea
          className="text-input"
          rows={2}
          value={description}
          maxLength={2000}
          aria-label="Description"
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <div className="rf-section">
        <div className="rf-section-head">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Ingredients *
          </span>
          <button className="btn btn-small" onClick={() => setShowKeys((v) => !v)}>
            {showKeys ? "Hide mapping keys" : "Mapping keys (advanced)"}
          </button>
        </div>
        {ings.map((r, i) => (
          <div key={i} className="rf-ing-row">
            <input
              type="text"
              className="text-input"
              placeholder="Ingredient"
              value={r.displayName}
              maxLength={160}
              aria-label={`Ingredient ${i + 1} name`}
              onChange={(e) => setIng(i, { displayName: e.target.value })}
            />
            <input
              type="number"
              className="text-input num-input"
              placeholder="qty"
              value={r.quantity}
              aria-label={`Ingredient ${i + 1} quantity`}
              onChange={(e) => setIng(i, { quantity: e.target.value })}
            />
            <input
              type="text"
              className="text-input rf-unit"
              placeholder="unit"
              value={r.unit}
              maxLength={16}
              aria-label={`Ingredient ${i + 1} unit`}
              onChange={(e) => setIng(i, { unit: e.target.value })}
            />
            <input
              type="text"
              className="text-input rf-prep"
              placeholder="preparation"
              value={r.preparation}
              maxLength={80}
              aria-label={`Ingredient ${i + 1} preparation`}
              onChange={(e) => setIng(i, { preparation: e.target.value })}
            />
            <label className="rf-opt">
              <input
                type="checkbox"
                checked={r.optional}
                onChange={(e) => setIng(i, { optional: e.target.checked })}
              />
              opt
            </label>
            <button
              className="btn btn-small"
              aria-label={`Remove ingredient ${i + 1}`}
              onClick={() => setIngs((xs) => xs.filter((_, xi) => xi !== i))}
            >
              ✕
            </button>
            {showKeys && (
              <input
                type="text"
                className="text-input rf-key"
                value={r.keyTouched ? r.mappingKey : deriveKey(r.displayName)}
                maxLength={160}
                aria-label={`Ingredient ${i + 1} mapping key`}
                onChange={(e) => setIng(i, { mappingKey: e.target.value, keyTouched: true })}
              />
            )}
          </div>
        ))}
        <div>
          <button
            className="btn btn-small"
            onClick={() =>
              setIngs((xs) => [
                ...xs,
                { displayName: "", mappingKey: "", keyTouched: false, quantity: "", unit: "", preparation: "", optional: false },
              ])
            }
          >
            + ingredient
          </button>
        </div>
      </div>

      <div className="rf-section">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Method *
        </span>
        {steps.map((s, i) => (
          <div key={i} className="rf-step-row">
            <span className="mp-num" style={{ fontSize: 13, color: "var(--mp-terra)" }}>
              {String(i + 1).padStart(2, "0")}
            </span>
            <textarea
              className="text-input"
              rows={1}
              value={s.instruction}
              aria-label={`Step ${i + 1} instruction`}
              onChange={(e) =>
                setSteps((xs) => xs.map((x, xi) => (xi === i ? { ...x, instruction: e.target.value } : x)))
              }
            />
            <input
              type="number"
              className="text-input num-input"
              placeholder="min"
              value={s.durationMinutes}
              aria-label={`Step ${i + 1} duration minutes`}
              onChange={(e) =>
                setSteps((xs) => xs.map((x, xi) => (xi === i ? { ...x, durationMinutes: e.target.value } : x)))
              }
            />
            <button
              className="btn btn-small"
              aria-label={`Remove step ${i + 1}`}
              onClick={() => setSteps((xs) => xs.filter((_, xi) => xi !== i))}
            >
              ✕
            </button>
          </div>
        ))}
        <div>
          <button
            className="btn btn-small"
            onClick={() => setSteps((xs) => [...xs, { instruction: "", durationMinutes: "" }])}
          >
            + step
          </button>
        </div>
      </div>

      <div className="rf-section">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Metadata *
        </span>
        <div className="rf-meta-grid">
          <label>
            <span className="field-label">Servings *</span>
            <input type="number" className="text-input" min={1} value={servings}
              onChange={(e) => setServings(e.target.value)} aria-label="Servings" />
          </label>
          <label>
            <span className="field-label">Prep min *</span>
            <input type="number" className="text-input" min={0} value={prepMins}
              onChange={(e) => setPrepMins(e.target.value)} aria-label="Prep minutes" />
          </label>
          <label>
            <span className="field-label">Cook min *</span>
            <input type="number" className="text-input" min={0} value={cookMins}
              onChange={(e) => setCookMins(e.target.value)} aria-label="Cook minutes" />
          </label>
          <label>
            <span className="field-label">Total min *</span>
            <input type="number" className="text-input" min={0} value={totalMins}
              onChange={(e) => setTotalMins(e.target.value)} aria-label="Total minutes" />
          </label>
          <label>
            <span className="field-label">Fridge days</span>
            <input type="number" className="text-input" min={0} value={fridgeDays}
              onChange={(e) => setFridgeDays(e.target.value)} aria-label="Fridge days" />
          </label>
          <label>
            <span className="field-label">Freezer weeks</span>
            <input type="number" className="text-input" min={0} value={freezerWeeks}
              onChange={(e) => setFreezerWeeks(e.target.value)} aria-label="Freezer weeks" />
          </label>
        </div>
        <div className="rf-grid2">
          <label>
            <span className="field-label">Equipment (comma-separated)</span>
            <input type="text" className="text-input" value={equipment}
              onChange={(e) => setEquipment(e.target.value)} aria-label="Equipment" />
          </label>
          <label>
            <span className="field-label">Meal types (comma-separated)</span>
            <input type="text" className="text-input" value={mealTypes}
              onChange={(e) => setMealTypes(e.target.value)} aria-label="Meal types" />
          </label>
        </div>
        <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13 }}>
          <input type="checkbox" checked={packable} onChange={(e) => setPackable(e.target.checked)} />
          Packable (lunchbox-safe)
        </label>
      </div>

      <details className="micros-details">
        <summary>Tags — AI inference fills these if absent</summary>
        <div className="rf-meta-grid" style={{ marginTop: 10 }}>
          <label>
            <span className="field-label">Protein</span>
            <input type="text" className="text-input" value={protein} maxLength={64}
              onChange={(e) => setProtein(e.target.value)} aria-label="Protein tag" />
          </label>
          <label>
            <span className="field-label">Cooking method</span>
            <input type="text" className="text-input" value={cookingMethod} maxLength={64}
              onChange={(e) => setCookingMethod(e.target.value)} aria-label="Cooking method tag" />
          </label>
          <label>
            <span className="field-label">Complexity</span>
            <select className="text-input time-select" value={complexity}
              onChange={(e) => setComplexity(e.target.value)} aria-label="Complexity">
              <option value="">—</option>
              <option value="MINIMAL">MINIMAL</option>
              <option value="MODERATE">MODERATE</option>
              <option value="INVOLVED">INVOLVED</option>
            </select>
          </label>
        </div>
        <div className="rf-grid2" style={{ marginTop: 8 }}>
          <label>
            <span className="field-label">Flavour profile (comma-separated)</span>
            <input type="text" className="text-input" value={flavour}
              onChange={(e) => setFlavour(e.target.value)} aria-label="Flavour profile" />
          </label>
          <label>
            <span className="field-label">Dietary flags (comma-separated)</span>
            <input type="text" className="text-input" value={dietary}
              onChange={(e) => setDietary(e.target.value)} aria-label="Dietary flags" />
          </label>
        </div>
      </details>

      {extra}

      {tried && errors.length > 0 && (
        <div className="rf-errors" role="alert">
          {errors.map((e) => (
            <div key={e}>· {e}</div>
          ))}
        </div>
      )}

      <div className="modal-actions">
        <button className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button className="btn btn-primary" onClick={submit}>
          {submitLabel}
        </button>
      </div>
    </div>
  );
}

/** Empty form skeleton for "start from scratch". */
export function emptyRecipeRequest(): CreateRecipeRequest {
  return {
    name: "",
    description: null,
    ingredients: [],
    method: [{ stepNumber: 1, instruction: "", durationMinutes: null }],
    metadata: {
      servings: 2,
      prepTimeMins: 10,
      cookTimeMins: 20,
      totalTimeMins: 30,
      equipmentRequired: [],
      fridgeDays: null,
      freezerWeeks: null,
      packable: false,
      cuisine: null,
      mealTypes: ["DINNER"],
    },
    tags: null,
  };
}
