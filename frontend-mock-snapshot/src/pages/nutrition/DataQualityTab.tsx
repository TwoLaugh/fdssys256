/**
 * Data quality tab — spec §6: needs-review queue of low-confidence
 * ingredient mappings (expand → per-100 g table → correction form) and a
 * cache-only search.
 */

import { useState } from "react";
import {
  correctIngredient,
  searchIngredients,
  useStore,
} from "../../mock/store";
import type {
  IngredientNutritionDocument,
  IngredientNutritionDto,
} from "../../mock/types";
import {
  fmtG,
  fmtKcal,
  microLabel,
  MicroRowsEditor,
  microsFromRows,
  microUnit,
  rowsFromMicros,
  shortDate,
  SourceBadge,
  type MicroRow,
} from "./shared";

const SCALARS: Array<{
  key: keyof IngredientNutritionDocument & string;
  label: string;
  unit: string;
}> = [
  { key: "calories", label: "Calories", unit: "kcal" },
  { key: "proteinG", label: "Protein", unit: "g" },
  { key: "carbsG", label: "Carbs", unit: "g" },
  { key: "fatG", label: "Fat", unit: "g" },
  { key: "fibreG", label: "Fibre", unit: "g" },
  { key: "saturatedFatG", label: "Saturated fat", unit: "g" },
  { key: "sugarG", label: "Sugar", unit: "g" },
];

function ConfidencePill({ confidence }: { confidence: number }) {
  const pct = `${Math.round(confidence * 100)}%`;
  return confidence < 0.85 ? (
    <span className="tint-chip amber">{pct} confidence</span>
  ) : (
    <span className="tint-chip olive">{pct} confidence</span>
  );
}

function Per100Table({ doc }: { doc: IngredientNutritionDocument }) {
  const maps: Array<[string, Record<string, number> | undefined]> = [
    ["micros", doc.micros],
    ["vitamins", doc.vitamins],
  ];
  return (
    <table className="nv-table" style={{ marginTop: 12, maxWidth: 460 }}>
      <thead>
        <tr>
          <th>Per 100 g</th>
          <th>Value</th>
        </tr>
      </thead>
      <tbody>
        {SCALARS.map(({ key, label, unit }) => {
          const v = doc[key] as number | null | undefined;
          return (
            <tr key={key}>
              <td>{label}</td>
              <td>
                {v == null
                  ? "—"
                  : `${unit === "kcal" ? fmtKcal(v) : fmtG(v)} ${unit}`}
              </td>
            </tr>
          );
        })}
        {maps.flatMap(([group, m]) =>
          Object.entries(m ?? {}).map(([k, v]) => (
            <tr key={`${group}-${k}`}>
              <td>
                {microLabel(k)}{" "}
                <span className="inline-note">({group})</span>
              </td>
              <td>
                {fmtG(v)} {microUnit(k)}
              </td>
            </tr>
          )),
        )}
      </tbody>
    </table>
  );
}

function CorrectForm({
  row,
  onDone,
}: {
  row: IngredientNutritionDto;
  onDone: () => void;
}) {
  const doc = row.nutritionPer100g;
  const [vals, setVals] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      SCALARS.map(({ key }) => {
        const v = doc[key] as number | null | undefined;
        return [key, v == null ? "" : String(v)];
      }),
    ),
  );
  const [microRows, setMicroRows] = useState<MicroRow[]>(
    rowsFromMicros(doc.micros),
  );
  const [vitaminRows, setVitaminRows] = useState<MicroRow[]>(
    rowsFromMicros(doc.vitamins),
  );

  const num = (v: string): number | null => {
    if (v.trim() === "") return null;
    const n = Number(v);
    return Number.isFinite(n) && n >= 0 ? n : null;
  };

  const save = () => {
    correctIngredient(row.searchTerm, {
      calories: num(vals.calories),
      proteinG: num(vals.proteinG),
      carbsG: num(vals.carbsG),
      fatG: num(vals.fatG),
      fibreG: num(vals.fibreG),
      saturatedFatG: num(vals.saturatedFatG),
      sugarG: num(vals.sugarG),
      micros: microsFromRows(microRows),
      vitamins: microsFromRows(vitaminRows),
    });
    onDone();
  };

  return (
    <div className="mp-card" style={{ padding: "14px 18px", marginTop: 12 }}>
      <span className="mp-label">Correct “{row.searchTerm}” · per 100 g</span>
      <div
        style={{ display: "flex", gap: 12, flexWrap: "wrap", marginTop: 10 }}
      >
        {SCALARS.map(({ key, label, unit }) => (
          <label key={key} style={{ display: "grid", gap: 4 }}>
            <span className="field-label">
              {label} {unit}
            </span>
            <input
              type="number"
              className="text-input num-input"
              min={0}
              value={vals[key]}
              onChange={(e) =>
                setVals((v) => ({ ...v, [key]: e.target.value }))
              }
              aria-label={`${row.searchTerm} ${label} per 100 g`}
            />
          </label>
        ))}
      </div>
      <div style={{ display: "grid", gap: 10, marginTop: 14 }}>
        <span className="field-label">micros</span>
        <MicroRowsEditor
          rows={microRows}
          onChange={setMicroRows}
          idPrefix={`${row.searchTerm} micros`}
        />
        <span className="field-label">vitamins</span>
        <MicroRowsEditor
          rows={vitaminRows}
          onChange={setVitaminRows}
          idPrefix={`${row.searchTerm} vitamins`}
        />
      </div>
      <div className="modal-actions">
        <button className="btn" onClick={onDone}>
          Cancel
        </button>
        <button className="btn btn-primary" onClick={save}>
          Save correction
        </button>
      </div>
      <div className="inline-note">
        saving flips the source to manual, confidence to 100%, and removes the
        row from this queue
      </div>
    </div>
  );
}

function IngredientRow({ row }: { row: IngredientNutritionDto }) {
  const [open, setOpen] = useState(false);
  const [correcting, setCorrecting] = useState(false);
  return (
    <div className="mp-card section-card">
      <div
        style={{
          display: "flex",
          gap: 10,
          alignItems: "center",
          flexWrap: "wrap",
        }}
      >
        <strong style={{ fontSize: 15 }}>{row.searchTerm}</strong>
        <SourceBadge source={row.source} />
        <ConfidencePill confidence={row.confidence} />
        <span className="inline-note">
          {row.lastVerifiedAt
            ? `verified ${shortDate(row.lastVerifiedAt.slice(0, 10))}`
            : "never verified"}
        </span>
        <span style={{ flex: 1 }} />
        <button className="btn btn-small" onClick={() => setOpen((o) => !o)}>
          {open ? "Hide" : "Details"}
        </button>
      </div>
      {open && (
        <>
          <Per100Table doc={row.nutritionPer100g} />
          {correcting ? (
            <CorrectForm row={row} onDone={() => setCorrecting(false)} />
          ) : (
            <button
              className="btn btn-small"
              style={{ marginTop: 12 }}
              onClick={() => setCorrecting(true)}
            >
              Correct
            </button>
          )}
        </>
      )}
    </div>
  );
}

export function DataQualityTab() {
  const cache = useStore((s) => s.nutrition.ingredientCache);
  const [query, setQuery] = useState("");
  const [resultTerms, setResultTerms] = useState<string[] | null>(null);

  const needsReview = cache.filter((r) => r.needsReview);
  // Resolve result rows live against the cache so corrections show through.
  const results =
    resultTerms === null
      ? null
      : cache.filter((r) => resultTerms.includes(r.searchTerm));

  const search = () => {
    setResultTerms(searchIngredients(cache, query, 20).map((r) => r.searchTerm));
  };

  return (
    <div>
      <div style={{ display: "flex", gap: 8, marginTop: 18, maxWidth: 460 }}>
        <input
          type="text"
          className="text-input"
          style={{ flex: 1, minWidth: 0 }}
          placeholder="Search your ingredient cache"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && search()}
          aria-label="Search ingredients"
        />
        <button className="btn" onClick={search} disabled={!query.trim()}>
          Search
        </button>
      </div>
      <div className="inline-note" style={{ marginTop: 6 }}>
        searching your cache — live USDA search lands in a later release
      </div>

      {results !== null && (
        <section aria-label="Search results" style={{ marginTop: 18 }}>
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Results · {results.length}
            </span>
          </div>
          {results.length === 0 ? (
            <div className="intake-meta" style={{ padding: "10px 0" }}>
              No matches in your cache.
            </div>
          ) : (
            results.map((r) => <IngredientRow key={r.searchTerm} row={r} />)
          )}
        </section>
      )}

      <div className="group-head" style={{ marginTop: 26 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Needs review · {needsReview.length}
        </span>
      </div>
      {needsReview.length === 0 ? (
        <div className="intake-meta" style={{ padding: "10px 0" }}>
          Queue is clear — every mapping is verified.
        </div>
      ) : (
        needsReview.map((r) => <IngredientRow key={r.searchTerm} row={r} />)
      )}
    </div>
  );
}
