import { useEffect, useRef, useState } from "react";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { TintChip } from "../components/TintChip";
import {
  markLifestyleReviewed,
  refreshTasteProfile,
  rollbackTasteProfile,
  saveHardConstraints,
  saveLifestyleConfig,
  saveTasteProfile,
  selectArchiveActiveCount,
  useStore,
} from "../mock/store";
import type {
  DietaryIdentityExceptionDto,
  HardConstraintsDto,
  HardIntoleranceDto,
  PreferenceLifestyleConfigDocument,
  TasteProfileChangeType,
  TasteProfileDocument,
  TasteProfileVersionDto,
  Tier1RemovalConfirmationProblem,
} from "../mock/types";
import { shortWhen } from "./recipes/shared";

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
const same = (a: unknown, b: unknown): boolean =>
  JSON.stringify(a) === JSON.stringify(b);

/* ================= shared bits ====================================================== */

function ChipEditor({
  values,
  onChange,
  tint = false,
  placeholder,
  maxItems,
  maxLength = 64,
}: {
  values: string[];
  onChange: (next: string[]) => void;
  tint?: boolean;
  placeholder: string;
  maxItems?: number;
  maxLength?: number;
}) {
  const [draft, setDraft] = useState("");
  const add = () => {
    const v = draft.trim();
    if (!v || values.some((x) => x.toLowerCase() === v.toLowerCase())) return;
    if (maxItems !== undefined && values.length >= maxItems) return;
    onChange([...values, v]);
    setDraft("");
  };
  return (
    <div>
      <div className="pref-chips">
        {values.map((v) => (
          <span key={v} className={tint ? "constraint-chip tinted" : "constraint-chip"}>
            {v}
            <button
              type="button"
              className="chip-x"
              aria-label={`Remove ${v}`}
              onClick={() => onChange(values.filter((x) => x !== v))}
            >
              ✕
            </button>
          </span>
        ))}
      </div>
      <div style={{ display: "flex", gap: 6, marginTop: 6 }}>
        <input
          type="text"
          className="text-input"
          style={{ flex: 1, minWidth: 0 }}
          placeholder={placeholder}
          maxLength={maxLength}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && add()}
          aria-label={placeholder}
        />
        <button className="btn btn-small" onClick={add} disabled={!draft.trim()}>
          Add
        </button>
      </div>
    </div>
  );
}

const SOURCE_BADGE: Record<string, string> = {
  FEEDBACK: "you said",
  INFERRED: "advisor guess",
  ONBOARDING: "from your quiz",
};

const CHANGE_TYPE_LABEL: Record<TasteProfileChangeType, string> = {
  INITIALIZED: "initialised",
  MANUAL_OVERRIDE: "manual override",
  AI_DELTA_APPLIED: "ai delta",
  REFRESH_TRIGGERED: "refresh",
  ROLLED_BACK: "rolled back",
};

function PrefCard({
  title,
  caption,
  children,
}: {
  title: string;
  caption?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mp-card side-card">
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        {title}
      </span>
      {children}
      {caption && <div className="grocery-footnote" style={{ marginTop: 10 }}>{caption}</div>}
    </div>
  );
}

/* ================= taste profile — read-only card mapping (§3b) ===================== */

function TasteDocCards({ doc }: { doc: TasteProfileDocument }) {
  const ing = doc.ingredientPreferences ?? {};
  const empty = (label: string) => (
    <div className="intake-meta" style={{ marginTop: 8 }}>
      nothing learned yet — {label}
    </div>
  );
  return (
    <div className="pref-grid">
      <PrefCard
        title="Mild intolerances"
        caption="Soft — severe intolerances live under hard constraints."
      >
        {(doc.softConstraints?.intolerances ?? []).length === 0
          ? empty("no soft intolerances")
          : (doc.softConstraints?.intolerances ?? []).map((row) => (
              <div key={row.substance} className="target-row">
                <span className="target-label">{row.substance}</span>
                <span style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <span className="mp-chip amber">{row.severity}</span>
                  {row.notes && <span className="intake-meta">{row.notes}</span>}
                </span>
              </div>
            ))}
      </PrefCard>

      <PrefCard title="Flavours">
        <div className="pref-chip-block">
          <span className="mp-label">Likes</span>
          <div className="pref-chips">
            {(doc.flavourPreferences?.likes ?? []).map((v) => (
              <TintChip key={v}>{v}</TintChip>
            ))}
          </div>
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Dislikes</span>
          <div className="pref-chips">
            {(doc.flavourPreferences?.dislikes ?? []).map((v) => (
              <span key={v} className="tier-badge">{v}</span>
            ))}
          </div>
        </div>
        {doc.flavourPreferences?.notes && (
          <div style={{ marginTop: 10 }}>
            <span className="mp-serif" style={{ fontSize: 15.5 }}>
              {doc.flavourPreferences.notes}
            </span>
          </div>
        )}
      </PrefCard>

      <PrefCard title="Textures">
        <div className="pref-chip-block">
          <span className="mp-label">Likes</span>
          <div className="pref-chips">
            {(doc.texturePreferences?.likes ?? []).map((v) => (
              <TintChip key={v}>{v}</TintChip>
            ))}
          </div>
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Dislikes</span>
          <div className="pref-chips">
            {(doc.texturePreferences?.dislikes ?? []).map((v) => (
              <span key={v} className="tier-badge">{v}</span>
            ))}
          </div>
        </div>
      </PrefCard>

      <PrefCard
        title="Ingredients"
        caption="Evidence is the confidence currency — ×23 steers harder than ×2."
      >
        {(ing.favourites ?? []).length === 0 && (ing.disliked ?? []).length === 0
          ? empty("no ingredient signals")
          : (
            <>
              {(ing.favourites ?? []).map((p) => (
                <div key={p.item} className="ingredient-pref-row">
                  <TintChip>{p.item}</TintChip>
                  <span className="evidence-count">×{p.evidenceCount ?? 0}</span>
                  {p.lastSignal && <span className="intake-meta">{shortWhen(p.lastSignal)}</span>}
                  {p.source && <span className="source-badge">{SOURCE_BADGE[p.source]}</span>}
                </div>
              ))}
              {(ing.disliked ?? []).map((p) => (
                <div key={p.item} className="ingredient-pref-row">
                  <span className="tier-badge">{p.item}</span>
                  <span className="evidence-count">×{p.evidenceCount ?? 0}</span>
                  {p.lastSignal && <span className="intake-meta">{shortWhen(p.lastSignal)}</span>}
                  {p.source && <span className="source-badge">{SOURCE_BADGE[p.source]}</span>}
                </div>
              ))}
            </>
          )}
      </PrefCard>

      <PrefCard
        title="Trending"
        caption="2 agreeing signals and counting — promoted at the third (the three-event rule)."
      >
        <div className="pref-chips" style={{ marginTop: 8 }}>
          {(ing.trendingPositive ?? []).map((t) => (
            <span key={t.item} className="mp-chip">
              ↑ {t.item} ×{t.evidenceCount ?? 0}
              {t.firstSignal && ` · since ${shortWhen(t.firstSignal)}`}
            </span>
          ))}
          {(ing.trendingNegative ?? []).map((t) => (
            <span key={t.item} className="mp-chip amber">
              ↓ {t.item} ×{t.evidenceCount ?? 0}
              {t.firstSignal && ` · since ${shortWhen(t.firstSignal)}`}
            </span>
          ))}
          {(ing.trendingPositive ?? []).length === 0 &&
            (ing.trendingNegative ?? []).length === 0 &&
            empty("no trends forming")}
        </div>
      </PrefCard>

      <PrefCard title="Cuisines">
        {(["favourites", "enjoys", "lessPreferred"] as const).map((band) => (
          <div key={band} className="pref-chip-block">
            <span className="mp-label">
              {band === "favourites" ? "Favourite" : band === "enjoys" ? "Enjoys" : "Less preferred"}
            </span>
            <div className="pref-chips">
              {(doc.cuisinePreferences?.[band] ?? []).map((v) =>
                band === "lessPreferred" ? (
                  <span key={v} className="tier-badge">{v}</span>
                ) : (
                  <TintChip key={v}>{v}</TintChip>
                ),
              )}
            </div>
          </div>
        ))}
        {doc.cuisinePreferences?.notes && (
          <div style={{ marginTop: 10 }}>
            <span className="mp-serif" style={{ fontSize: 15.5 }}>
              {doc.cuisinePreferences.notes}
            </span>
          </div>
        )}
      </PrefCard>

      <PrefCard title="Cooking">
        <div style={{ marginTop: 8 }}>
          <span className="mp-chip">{doc.cookingPreferences?.skillLevel ?? "—"}</span>
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Preferred methods</span>
          <div className="pref-chips">
            {(doc.cookingPreferences?.preferredMethods ?? []).map((v) => (
              <TintChip key={v}>{v}</TintChip>
            ))}
          </div>
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Avoided</span>
          <div className="pref-chips">
            {(doc.cookingPreferences?.dislikedMethods ?? []).map((v) => (
              <span key={v} className="tier-badge">{v}</span>
            ))}
          </div>
        </div>
      </PrefCard>

      <PrefCard title="Portions">
        <div className="dialog-body" style={{ marginTop: 8 }}>
          {doc.portionStyle?.preference ?? "Nothing learned yet."}
          {doc.portionStyle?.saladMeals && ` Salad meals: ${doc.portionStyle.saladMeals}`}
        </div>
      </PrefCard>

      <PrefCard title="Household">
        <div className="pref-chip-block">
          <span className="mp-label">Just for you</span>
          <div className="pref-chips">
            {(doc.householdContext?.individualOnlyPreferences ?? []).map((v) => (
              <TintChip key={v}>{v}</TintChip>
            ))}
          </div>
        </div>
        {doc.householdContext?.householdSuitableNotes && (
          <div className="intake-meta" style={{ marginTop: 8 }}>
            {doc.householdContext.householdSuitableNotes}
          </div>
        )}
      </PrefCard>

      <PrefCard title="Repeat / avoid">
        {(doc.recipesToRepeat ?? []).map((r) => (
          <div key={r.name} className="target-row">
            <span className="target-label">{r.name}</span>
            <span style={{ display: "flex", gap: 8, alignItems: "center" }}>
              {r.suitableFor && <span className="mp-chip muted">{r.suitableFor}</span>}
              {r.reason && <span className="intake-meta" style={{ fontStyle: "italic" }}>{r.reason}</span>}
            </span>
          </div>
        ))}
        {(doc.recipesToAvoid ?? []).map((r) => (
          <div key={r.name} className="target-row">
            <span className="target-label" style={{ textDecoration: "line-through" }}>
              {r.name}
            </span>
            {r.reason && <span className="intake-meta" style={{ fontStyle: "italic" }}>{r.reason}</span>}
          </div>
        ))}
        {(doc.recipesToRepeat ?? []).length === 0 &&
          (doc.recipesToAvoid ?? []).length === 0 &&
          empty("no repeat or avoid list")}
      </PrefCard>

      <PrefCard title="Experiments">
        {(doc.activeExperiments ?? []).length === 0
          ? empty("no live hypotheses")
          : (doc.activeExperiments ?? []).map((x) => (
              <div key={x.hypothesis} style={{ marginTop: 10 }}>
                <span className="mp-serif" style={{ fontSize: 15.5 }}>{x.hypothesis}</span>
                <div style={{ display: "flex", gap: 8, marginTop: 4, alignItems: "center" }}>
                  {x.status === "TESTING" ? (
                    <span className="mp-chip amber">testing</span>
                  ) : x.status === "PROMOTED" ? (
                    <TintChip>promoted</TintChip>
                  ) : (
                    <span className="mp-chip muted">discarded</span>
                  )}
                  <span className="intake-meta">
                    {x.evidenceFor ?? 0} for / {x.evidenceAgainst ?? 0} against
                  </span>
                  {x.created && <span className="intake-meta">{shortWhen(x.created)}</span>}
                </div>
              </div>
            ))}
      </PrefCard>

      <PrefCard title="Insights">
        {(doc.learnedInsights ?? []).length === 0
          ? empty("no insights yet")
          : (doc.learnedInsights ?? []).map((line) => (
              <div key={line} style={{ marginTop: 8 }}>
                <span className="mp-serif" style={{ fontSize: 15.5 }}>· {line}</span>
              </div>
            ))}
      </PrefCard>
    </div>
  );
}

/* ================= taste profile — edit mode (§3d) ================================== */

function TasteDocEditor({
  draft,
  setDraft,
}: {
  draft: TasteProfileDocument;
  setDraft: (next: TasteProfileDocument) => void;
}) {
  const patch = (p: Partial<TasteProfileDocument>) => setDraft({ ...draft, ...p });
  const ing = draft.ingredientPreferences ?? {};
  const today = "2026-06-10";

  const ingredientList = (
    key: "favourites" | "disliked",
    label: string,
    tint: boolean,
  ) => (
    <div className="pref-chip-block">
      <span className="mp-label">{label}</span>
      <ChipEditor
        tint={tint}
        values={(ing[key] ?? []).map((p) => p.item ?? "")}
        maxItems={50}
        maxLength={128}
        placeholder={`Add to ${label.toLowerCase()}`}
        onChange={(next) => {
          const prev = ing[key] ?? [];
          patch({
            ingredientPreferences: {
              ...ing,
              [key]: next.map(
                (item) =>
                  prev.find((p) => p.item === item) ?? {
                    item,
                    evidenceCount: 1,
                    lastSignal: today,
                    source: "FEEDBACK" as const,
                  },
              ),
            },
          });
        }}
      />
    </div>
  );

  return (
    <div className="pref-grid">
      <PrefCard title="Mild intolerances">
        {(draft.softConstraints?.intolerances ?? []).map((row, i) => (
          <div key={i} className="exception-row">
            <input
              className="text-input"
              value={row.substance ?? ""}
              maxLength={64}
              placeholder="substance"
              aria-label={`Intolerance ${i + 1} substance`}
              onChange={(e) =>
                patch({
                  softConstraints: {
                    intolerances: (draft.softConstraints?.intolerances ?? []).map((r, ri) =>
                      ri === i ? { ...r, substance: e.target.value } : r,
                    ),
                  },
                })
              }
            />
            <input
              className="text-input"
              value={row.severity ?? ""}
              maxLength={32}
              placeholder="severity"
              aria-label={`Intolerance ${i + 1} severity`}
              onChange={(e) =>
                patch({
                  softConstraints: {
                    intolerances: (draft.softConstraints?.intolerances ?? []).map((r, ri) =>
                      ri === i ? { ...r, severity: e.target.value } : r,
                    ),
                  },
                })
              }
            />
            <button
              className="btn btn-small"
              aria-label={`Remove intolerance ${i + 1}`}
              onClick={() =>
                patch({
                  softConstraints: {
                    intolerances: (draft.softConstraints?.intolerances ?? []).filter(
                      (_, ri) => ri !== i,
                    ),
                  },
                })
              }
            >
              ✕
            </button>
          </div>
        ))}
        <button
          className="btn btn-small"
          style={{ marginTop: 8 }}
          onClick={() =>
            patch({
              softConstraints: {
                intolerances: [
                  ...(draft.softConstraints?.intolerances ?? []),
                  { substance: "", severity: "mild", notes: null },
                ],
              },
            })
          }
        >
          + intolerance
        </button>
      </PrefCard>

      <PrefCard title="Flavours">
        <div className="pref-chip-block">
          <span className="mp-label">Likes (≤30)</span>
          <ChipEditor
            tint
            values={draft.flavourPreferences?.likes ?? []}
            maxItems={30}
            placeholder="Add a flavour like"
            onChange={(likes) =>
              patch({ flavourPreferences: { ...draft.flavourPreferences, likes } })
            }
          />
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Dislikes (≤30)</span>
          <ChipEditor
            values={draft.flavourPreferences?.dislikes ?? []}
            maxItems={30}
            placeholder="Add a flavour dislike"
            onChange={(dislikes) =>
              patch({ flavourPreferences: { ...draft.flavourPreferences, dislikes } })
            }
          />
        </div>
        <textarea
          className="text-input"
          style={{ width: "100%", marginTop: 8 }}
          rows={2}
          maxLength={512}
          placeholder="Notes (≤512)"
          value={draft.flavourPreferences?.notes ?? ""}
          aria-label="Flavour notes"
          onChange={(e) =>
            patch({
              flavourPreferences: { ...draft.flavourPreferences, notes: e.target.value || null },
            })
          }
        />
      </PrefCard>

      <PrefCard title="Textures">
        <div className="pref-chip-block">
          <span className="mp-label">Likes</span>
          <ChipEditor
            tint
            values={draft.texturePreferences?.likes ?? []}
            maxItems={30}
            placeholder="Add a texture like"
            onChange={(likes) =>
              patch({ texturePreferences: { ...draft.texturePreferences, likes } })
            }
          />
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Dislikes</span>
          <ChipEditor
            values={draft.texturePreferences?.dislikes ?? []}
            maxItems={30}
            placeholder="Add a texture dislike"
            onChange={(dislikes) =>
              patch({ texturePreferences: { ...draft.texturePreferences, dislikes } })
            }
          />
        </div>
      </PrefCard>

      <PrefCard
        title="Ingredients"
        caption="Manual additions start at ×1 evidence, source “you said”."
      >
        {ingredientList("favourites", "Favourites (≤50)", true)}
        {ingredientList("disliked", "Disliked (≤50)", false)}
      </PrefCard>

      <PrefCard title="Trending" caption="Pipeline-managed; removable here as an override.">
        <ChipEditor
          values={(ing.trendingPositive ?? []).map((t) => t.item ?? "")}
          placeholder="(removal only is meaningful)"
          onChange={(next) =>
            patch({
              ingredientPreferences: {
                ...ing,
                trendingPositive: (ing.trendingPositive ?? []).filter((t) =>
                  next.includes(t.item ?? ""),
                ),
              },
            })
          }
        />
      </PrefCard>

      <PrefCard title="Cuisines">
        {(["favourites", "enjoys", "lessPreferred"] as const).map((band) => (
          <div key={band} className="pref-chip-block">
            <span className="mp-label">
              {band === "favourites" ? "Favourite" : band === "enjoys" ? "Enjoys" : "Less preferred"}
            </span>
            <ChipEditor
              tint={band !== "lessPreferred"}
              values={draft.cuisinePreferences?.[band] ?? []}
              placeholder={`Add ${band === "lessPreferred" ? "a less-preferred" : "a"} cuisine`}
              onChange={(next) =>
                patch({ cuisinePreferences: { ...draft.cuisinePreferences, [band]: next } })
              }
            />
          </div>
        ))}
      </PrefCard>

      <PrefCard title="Cooking">
        <label style={{ display: "block", marginTop: 8 }}>
          <span className="field-label">Skill level</span>
          <select
            className="text-input time-select"
            value={draft.cookingPreferences?.skillLevel ?? "INTERMEDIATE"}
            aria-label="Skill level"
            onChange={(e) =>
              patch({
                cookingPreferences: {
                  ...draft.cookingPreferences,
                  skillLevel: e.target.value as NonNullable<
                    TasteProfileDocument["cookingPreferences"]
                  >["skillLevel"],
                },
              })
            }
          >
            <option value="BEGINNER">BEGINNER</option>
            <option value="INTERMEDIATE">INTERMEDIATE</option>
            <option value="ADVANCED">ADVANCED</option>
          </select>
        </label>
        <div className="pref-chip-block">
          <span className="mp-label">Preferred methods</span>
          <ChipEditor
            tint
            values={draft.cookingPreferences?.preferredMethods ?? []}
            placeholder="Add a method"
            onChange={(preferredMethods) =>
              patch({ cookingPreferences: { ...draft.cookingPreferences, preferredMethods } })
            }
          />
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Avoided methods</span>
          <ChipEditor
            values={draft.cookingPreferences?.dislikedMethods ?? []}
            placeholder="Add an avoided method"
            onChange={(dislikedMethods) =>
              patch({ cookingPreferences: { ...draft.cookingPreferences, dislikedMethods } })
            }
          />
        </div>
      </PrefCard>

      <PrefCard title="Portions">
        <input
          className="text-input"
          style={{ width: "100%", marginTop: 8 }}
          placeholder="Portion preference"
          value={draft.portionStyle?.preference ?? ""}
          aria-label="Portion preference"
          onChange={(e) =>
            patch({ portionStyle: { ...draft.portionStyle, preference: e.target.value || null } })
          }
        />
        <input
          className="text-input"
          style={{ width: "100%", marginTop: 8 }}
          placeholder="Salad meals"
          value={draft.portionStyle?.saladMeals ?? ""}
          aria-label="Salad meals"
          onChange={(e) =>
            patch({ portionStyle: { ...draft.portionStyle, saladMeals: e.target.value || null } })
          }
        />
      </PrefCard>

      <PrefCard title="Household">
        <div className="pref-chip-block">
          <span className="mp-label">Just for you</span>
          <ChipEditor
            tint
            values={draft.householdContext?.individualOnlyPreferences ?? []}
            placeholder="Add an individual-only preference"
            onChange={(individualOnlyPreferences) =>
              patch({
                householdContext: { ...draft.householdContext, individualOnlyPreferences },
              })
            }
          />
        </div>
        <input
          className="text-input"
          style={{ width: "100%", marginTop: 8 }}
          placeholder="Household-suitable notes"
          value={draft.householdContext?.householdSuitableNotes ?? ""}
          aria-label="Household notes"
          onChange={(e) =>
            patch({
              householdContext: {
                ...draft.householdContext,
                householdSuitableNotes: e.target.value || null,
              },
            })
          }
        />
      </PrefCard>

      <PrefCard title="Repeat / avoid" caption="≤50 each; reason is optional.">
        <div className="pref-chip-block">
          <span className="mp-label">Repeat</span>
          <ChipEditor
            tint
            values={(draft.recipesToRepeat ?? []).map((r) => r.name ?? "")}
            maxItems={50}
            placeholder="Add a recipe to repeat"
            onChange={(next) =>
              patch({
                recipesToRepeat: next.map(
                  (name) =>
                    (draft.recipesToRepeat ?? []).find((r) => r.name === name) ?? {
                      name,
                      suitableFor: null,
                      reason: "you added this",
                    },
                ),
              })
            }
          />
        </div>
        <div className="pref-chip-block">
          <span className="mp-label">Avoid</span>
          <ChipEditor
            values={(draft.recipesToAvoid ?? []).map((r) => r.name ?? "")}
            maxItems={50}
            placeholder="Add a recipe to avoid"
            onChange={(next) =>
              patch({
                recipesToAvoid: next.map(
                  (name) =>
                    (draft.recipesToAvoid ?? []).find((r) => r.name === name) ?? {
                      name,
                      suitableFor: null,
                      reason: "you added this",
                    },
                ),
              })
            }
          />
        </div>
      </PrefCard>

      <PrefCard title="Experiments" caption="Delete a hypothesis the advisor should stop testing.">
        {(draft.activeExperiments ?? []).map((x, i) => (
          <div key={i} className="target-row">
            <span className="target-label" style={{ whiteSpace: "normal" }}>{x.hypothesis}</span>
            <button
              className="btn btn-small"
              aria-label={`Delete experiment ${i + 1}`}
              onClick={() =>
                patch({
                  activeExperiments: (draft.activeExperiments ?? []).filter((_, ri) => ri !== i),
                })
              }
            >
              ✕
            </button>
          </div>
        ))}
      </PrefCard>

      <PrefCard title="Insights" caption="≤20 lines, ≤512 chars each.">
        <ChipEditor
          values={draft.learnedInsights ?? []}
          maxItems={20}
          maxLength={512}
          placeholder="Add an insight in your own words"
          onChange={(learnedInsights) => patch({ learnedInsights })}
        />
      </PrefCard>
    </div>
  );
}

/* ================= versions drawer (§3c) ============================================ */

function VersionsDrawer({
  versions,
  currentVersion,
  optimisticVersion,
  onClose,
}: {
  versions: TasteProfileVersionDto[];
  currentVersion: number;
  optimisticVersion: number;
  onClose: () => void;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const [rawId, setRawId] = useState<string | null>(null);
  const [restoring, setRestoring] = useState<TasteProfileVersionDto | null>(null);

  return (
    <Modal label="Taste profile versions" onClose={onClose} wide>
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Versions
      </span>
      <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
        {versions.map((v) => {
          const isCurrent = v.documentVersion === currentVersion;
          const deltas = Array.isArray(v.deltasApplied) ? v.deltasApplied.length : null;
          return (
            <div key={v.id} className="mp-card" style={{ padding: "12px 16px" }}>
              <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
                <span className={isCurrent ? "version-current" : "version-old"}>
                  v{v.documentVersion}
                  {isCurrent && " current"}
                </span>
                <span className="mp-chip muted">{v.trigger.toLowerCase()}</span>
                <span className="intake-meta">{shortWhen(v.generatedAt)}</span>
                <span className="intake-meta">model {v.modelTierUsed.toLowerCase()}</span>
                {v.feedbackRangeStart && v.feedbackRangeEnd && (
                  <span className="intake-meta">
                    signals {v.feedbackRangeStart} → {v.feedbackRangeEnd}
                  </span>
                )}
                {deltas !== null && (
                  <button className="link-btn" onClick={() => setRawId(rawId === v.id ? null : v.id)}>
                    {deltas} delta{deltas === 1 ? "" : "s"}
                  </button>
                )}
                <span style={{ flex: 1 }} />
                <button
                  className="btn btn-small"
                  onClick={() => setOpenId(openId === v.id ? null : v.id)}
                >
                  {openId === v.id ? "Hide snapshot" : "Preview snapshot"}
                </button>
                {!isCurrent && (
                  <button className="btn btn-small" onClick={() => setRestoring(v)}>
                    Restore
                  </button>
                )}
              </div>
              {rawId === v.id && (
                <pre className="raw-json">{JSON.stringify(v.deltasApplied, null, 2)}</pre>
              )}
              {openId === v.id && (
                <div className="drawer-snapshot">
                  <TasteDocCards doc={v.documentSnapshot} />
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        Restore replays feedback given since the snapshot — the result lands as
        a NEW version, never a decrement.
      </div>
      {restoring && (
        <Modal label="Restore version" onClose={() => setRestoring(null)}>
          <div className="dialog-title">Restore v{restoring.documentVersion}?</div>
          <div className="dialog-body">
            Restores v{restoring.documentVersion} as a new version; feedback
            given since then is re-applied automatically.
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={() => setRestoring(null)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                const outcome = rollbackTasteProfile(
                  restoring.documentVersion,
                  optimisticVersion,
                );
                setRestoring(null);
                if (outcome === "ok") onClose();
              }}
            >
              Restore as new version
            </button>
          </div>
        </Modal>
      )}
    </Modal>
  );
}

/* ================= hard constraints (§4) ============================================ */

interface ConstraintsDraft {
  allergies: string[];
  medicalDiets: string[];
  base: string;
  labelForDisplay: string;
  exceptions: DietaryIdentityExceptionDto[];
  intolerances: HardIntoleranceDto[];
}

function draftFrom(dto: HardConstraintsDto): ConstraintsDraft {
  return {
    allergies: [...dto.allergies],
    medicalDiets: [...dto.medicalDiets],
    base: dto.dietaryIdentity.base,
    labelForDisplay: dto.dietaryIdentity.labelForDisplay ?? "",
    exceptions: clone(dto.dietaryIdentity.exceptions),
    intolerances: clone(dto.intolerances),
  };
}

const TIER1_LABEL: Record<string, (v: string) => string> = {
  ALLERGY: (v) => `allergy: ${v}`,
  MEDICAL_DIET: (v) => `medical diet: ${v}`,
  SEVERE_INTOLERANCE: (v) => `severe intolerance: ${v}`,
  DIETARY_IDENTITY_BASE: (v) => `dietary identity relaxed (was ${v})`,
};

function HardConstraintsSection() {
  const dto = useStore((s) => s.preferences.hardConstraints);
  const audit = useStore((s) => s.preferences.hardAudit);
  const [draft, setDraft] = useState<ConstraintsDraft | null>(null);
  const [problem, setProblem] = useState<Tier1RemovalConfirmationProblem | null>(null);
  const [rawAuditId, setRawAuditId] = useState<string | null>(null);

  if (!dto) {
    return (
      <div className="mp-card side-card">
        <span className="mp-label" style={{ color: "var(--mp-red)" }}>Hard constraints</span>
        <div className="page-loading">Finish onboarding to start here.</div>
      </div>
    );
  }

  const d = draft ?? draftFrom(dto);
  const set = (p: Partial<ConstraintsDraft>) => setDraft({ ...d, ...p });
  const dirty = draft !== null && !same(d, draftFrom(dto));

  const allergens = d.allergies.map((a) => a.toLowerCase());
  const substances = d.intolerances.map((i) => i.substance.toLowerCase());
  const exceptionIssue = (ex: DietaryIdentityExceptionDto): "collision" | "ambiguous" | null => {
    const allows = ex.allows.trim().toLowerCase();
    if (!allows) return null;
    if (allows.endsWith("_free")) {
      const baseSubstance = allows.slice(0, -"_free".length);
      return allergens.some((a) => a.includes(baseSubstance)) ||
        substances.some((s) => s.includes(baseSubstance))
        ? "ambiguous"
        : null;
    }
    return allergens.includes(allows) || substances.includes(allows) ? "collision" : null;
  };
  const hasCollision = d.exceptions.some((ex) => exceptionIssue(ex) === "collision");

  const buildRequest = (confirm: boolean) => ({
    allergies: d.allergies,
    medicalDiets: d.medicalDiets,
    dietaryIdentity: {
      base: d.base,
      labelForDisplay: d.labelForDisplay.trim() || null,
      exceptions: d.exceptions.filter((ex) => ex.allows.trim() !== ""),
    },
    intolerances: d.intolerances.filter((i) => i.substance.trim() !== ""),
    ageRestrictions: dto.ageRestrictions, // echoed back unchanged (auto-managed)
    expectedVersion: dto.version,
    ...(confirm ? { confirmTier1Removals: true } : {}),
  });

  const save = (confirm: boolean) => {
    const result = saveHardConstraints(buildRequest(confirm));
    if (result.kind === "tier1") {
      // Disambiguated from the optimistic-lock 409 by the reason slug (§4b).
      setProblem(result.problem);
    } else {
      setProblem(null);
      if (result.kind === "ok") setDraft(null);
    }
  };

  return (
    <div className="mp-card side-card constraints-card">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Hard constraints
        </span>
        <span className="mp-label" style={{ color: "var(--mp-red)" }}>
          Safety filtered
        </span>
      </div>
      <div className="intake-meta" style={{ marginTop: 4 }}>
        Never broken by any plan; never edited by AI.
      </div>

      <div className="pref-chip-block">
        <span className="mp-label">Allergies</span>
        <ChipEditor
          values={d.allergies}
          placeholder="Add an allergy"
          onChange={(allergies) => set({ allergies })}
        />
      </div>

      <div className="pref-chip-block">
        <span className="mp-label">Medical diets</span>
        <ChipEditor
          values={d.medicalDiets}
          placeholder="e.g. low_sodium, low_fodmap, diabetic"
          onChange={(medicalDiets) => set({ medicalDiets })}
        />
      </div>

      <div className="pref-chip-block">
        <span className="mp-label">Dietary identity</span>
        <div className="rf-grid2" style={{ marginTop: 6 }}>
          <label>
            <span className="field-label">Base</span>
            <select
              className="text-input time-select"
              value={d.base}
              aria-label="Dietary identity base"
              onChange={(e) => set({ base: e.target.value })}
            >
              {["omnivore", "vegetarian", "vegan", "pescatarian", "keto", "paleo", "other"].map(
                (b) => (
                  <option key={b} value={b}>
                    {b}
                  </option>
                ),
              )}
            </select>
          </label>
          <label>
            <span className="field-label">Display label (optional)</span>
            <input
              className="text-input"
              maxLength={64}
              value={d.labelForDisplay}
              placeholder="e.g. flexible vegetarian"
              aria-label="Identity display label"
              onChange={(e) => set({ labelForDisplay: e.target.value })}
            />
          </label>
        </div>
        {d.exceptions.map((ex, i) => {
          const issue = exceptionIssue(ex);
          return (
            <div key={i}>
              <div className="exception-row" style={{ marginTop: 8 }}>
                <input
                  className="text-input"
                  maxLength={64}
                  value={ex.allows}
                  placeholder="allows (fish, dairy, lactose_free…)"
                  aria-label={`Exception ${i + 1} allows`}
                  onChange={(e) =>
                    set({
                      exceptions: d.exceptions.map((x, xi) =>
                        xi === i ? { ...x, allows: e.target.value } : x,
                      ),
                    })
                  }
                />
                <input
                  className="text-input"
                  maxLength={32}
                  value={ex.frequency ?? ""}
                  placeholder="frequency (2-3x/week)"
                  title="Planner-scored, not filter-enforced"
                  aria-label={`Exception ${i + 1} frequency`}
                  onChange={(e) =>
                    set({
                      exceptions: d.exceptions.map((x, xi) =>
                        xi === i ? { ...x, frequency: e.target.value || null } : x,
                      ),
                    })
                  }
                />
                <select
                  className="text-input time-select"
                  value={ex.context}
                  aria-label={`Exception ${i + 1} context`}
                  onChange={(e) =>
                    set({
                      exceptions: d.exceptions.map((x, xi) =>
                        xi === i ? { ...x, context: e.target.value } : x,
                      ),
                    })
                  }
                >
                  {["any", "social", "weekend", "weekday"].map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
                <button
                  className="btn btn-small"
                  aria-label={`Remove exception ${i + 1}`}
                  onClick={() =>
                    set({ exceptions: d.exceptions.filter((_, xi) => xi !== i) })
                  }
                >
                  ✕
                </button>
              </div>
              {issue === "collision" && (
                <div className="rf-errors" role="alert" style={{ marginTop: 4 }}>
                  · “{ex.allows}” names a declared allergy/intolerance — the
                  server rejects this exception (400).
                </div>
              )}
              {issue === "ambiguous" && (
                <div className="inline-note" style={{ marginTop: 4 }}>
                  Untagged {ex.allows.replace(/_free$/i, "")} foods are still
                  flagged for review (AMBIGUOUS) — only items marked{" "}
                  {ex.allows.toLowerCase()} pass the filter.
                </div>
              )}
            </div>
          );
        })}
        <button
          className="btn btn-small"
          style={{ marginTop: 8 }}
          onClick={() =>
            set({ exceptions: [...d.exceptions, { allows: "", frequency: null, context: "any" }] })
          }
        >
          + exception
        </button>
      </div>

      <div className="pref-chip-block">
        <span className="mp-label">Severe intolerances</span>
        {d.intolerances.map((row, i) => (
          <div key={i} className="exception-row" style={{ marginTop: 8 }}>
            <input
              className="text-input"
              maxLength={64}
              value={row.substance}
              placeholder="substance"
              aria-label={`Severe intolerance ${i + 1} substance`}
              onChange={(e) =>
                set({
                  intolerances: d.intolerances.map((x, xi) =>
                    xi === i ? { ...x, substance: e.target.value } : x,
                  ),
                })
              }
            />
            <input
              className="text-input"
              maxLength={32}
              value={row.severity}
              placeholder="severity (e.g. coeliac)"
              aria-label={`Severe intolerance ${i + 1} severity`}
              onChange={(e) =>
                set({
                  intolerances: d.intolerances.map((x, xi) =>
                    xi === i ? { ...x, severity: e.target.value } : x,
                  ),
                })
              }
            />
            <input
              className="text-input"
              maxLength={255}
              value={row.notes ?? ""}
              placeholder="notes"
              aria-label={`Severe intolerance ${i + 1} notes`}
              onChange={(e) =>
                set({
                  intolerances: d.intolerances.map((x, xi) =>
                    xi === i ? { ...x, notes: e.target.value || null } : x,
                  ),
                })
              }
            />
            <button
              className="btn btn-small"
              aria-label={`Remove severe intolerance ${i + 1}`}
              onClick={() => set({ intolerances: d.intolerances.filter((_, xi) => xi !== i) })}
            >
              ✕
            </button>
          </div>
        ))}
        <button
          className="btn btn-small"
          style={{ marginTop: 8 }}
          onClick={() =>
            set({ intolerances: [...d.intolerances, { substance: "", severity: "", notes: null }] })
          }
        >
          + severe intolerance
        </button>
      </div>

      <div className="pref-chip-block">
        <span className="mp-label">Age restrictions</span>
        <div className="pref-chips">
          {dto.ageRestrictions.map((r) => (
            <span key={r.ruleKey} className="mp-chip muted">
              {r.ruleKey.replace(/_/g, " ")}
              {r.autoPopulated && " · auto"}
            </span>
          ))}
        </div>
        <div className="intake-meta" style={{ marginTop: 4 }}>
          Auto-managed for child profiles — echoed back unchanged on save.
        </div>
      </div>

      {dirty && (
        <div className="modal-actions" style={{ marginTop: 14 }}>
          <button className="btn" onClick={() => setDraft(null)}>
            Discard changes
          </button>
          <button
            className="btn btn-primary"
            disabled={hasCollision}
            title={hasCollision ? "Fix the exception collision first" : undefined}
            onClick={() => save(false)}
          >
            Save constraints
          </button>
        </div>
      )}

      <details className="micros-details" style={{ marginTop: 14 }}>
        <summary>Change history ({audit.length})</summary>
        <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
          {audit.map((row) => (
            <div key={row.id} className="history-row">
              <div style={{ minWidth: 0 }}>
                <div className="history-query">{row.fieldChanged}</div>
                <div className="history-meta">{shortWhen(row.occurredAt)}</div>
                {rawAuditId === row.id && (
                  <pre className="raw-json">
                    {JSON.stringify({ from: row.previousValueJson, to: row.newValueJson }, null, 2)}
                  </pre>
                )}
              </div>
              <button
                className="link-btn"
                onClick={() => setRawAuditId(rawAuditId === row.id ? null : row.id)}
              >
                {rawAuditId === row.id ? "hide diff" : "diff"}
              </button>
            </div>
          ))}
        </div>
      </details>

      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        Removing a Tier-1 constraint is never a silent one-step edit — the
        server rejects it (409) until you confirm.
      </div>

      {problem && (
        <Modal label="Confirm removal" onClose={() => setProblem(null)}>
          <span className="mp-label" style={{ color: "var(--mp-red)" }}>
            Remove safety constraints?
          </span>
          <div className="dialog-title">{problem.title}</div>
          <div style={{ display: "grid", gap: 6, marginTop: 10 }}>
            {problem.removedConstraints.map((rc) => (
              <div key={`${rc.category}-${rc.value}`} className="tier1-removal-line">
                ✕ {TIER1_LABEL[rc.category]?.(rc.value) ?? `${rc.category}: ${rc.value}`}
              </div>
            ))}
          </div>
          <p className="dialog-body">
            These protect every plan, recipe and grocery list. Remove anyway?
          </p>
          <div className="modal-actions">
            <button className="btn" onClick={() => setProblem(null)}>
              Keep them
            </button>
            <button
              className="btn btn-danger"
              onClick={() => {
                // Re-submit the SAME payload with confirmTier1Removals=true.
                save(true);
              }}
            >
              Remove anyway
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ================= lifestyle config (§5) ============================================ */

const LIFESTYLE_GROUPS = [
  "mealStructure",
  "mealTiming",
  "noveltyTolerance",
  "cookingContexts",
  "batchCooking",
  "reheatingPreferences",
  "eatingContext",
  "seasonalPreferences",
  "mealTypePreferences",
  "accompaniments",
  "groceryQualityPreferences",
  "pantryTracking",
] as const;

const GROUP_LABEL: Record<(typeof LIFESTYLE_GROUPS)[number], string> = {
  mealStructure: "Meal structure",
  mealTiming: "Meal timing",
  noveltyTolerance: "Novelty tolerance",
  cookingContexts: "Cooking contexts",
  batchCooking: "Batch cooking",
  reheatingPreferences: "Reheating",
  eatingContext: "Eating context",
  seasonalPreferences: "Seasonal",
  mealTypePreferences: "Meal-type",
  accompaniments: "Accompaniments",
  groceryQualityPreferences: "Grocery quality",
  pantryTracking: "Pantry tracking",
};

const NOVELTY_MODES = ["rotation", "batch_repeat", "high_variety", "static"] as const;
const QUALITY_OPTIONS = [
  "always",
  "preferred",
  "when_price_comparable",
  "own_label_default",
  "no_preference",
] as const;

function numOrUndef(v: string): number | undefined {
  if (v.trim() === "") return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

function LifestyleSection() {
  const dto = useStore((s) => s.preferences.lifestyle);
  const audit = useStore((s) => s.preferences.lifestyleAudit);
  const [draft, setDraft] = useState<PreferenceLifestyleConfigDocument | null>(null);
  const [auditFilter, setAuditFilter] = useState<string | null>(null);
  const [rawAuditId, setRawAuditId] = useState<string | null>(null);

  if (!dto) {
    return (
      <div className="mp-card side-card">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>Lifestyle</span>
        <div className="page-loading">Finish onboarding to start here.</div>
      </div>
    );
  }

  const doc = draft ?? clone(dto.document);
  const dirty = draft !== null && !same(doc, dto.document);
  const patch = (p: Partial<PreferenceLifestyleConfigDocument>) =>
    setDraft({ ...doc, ...p });

  // Pre-empts the 400 with offendingMode/offendingField extensions (§5b).
  const noveltyErrors: string[] = Object.entries(doc.noveltyTolerance?.bySlot ?? {}).flatMap(
    ([slot, mode]) => {
      const m = mode.mode ?? "";
      if (!NOVELTY_MODES.includes(m as (typeof NOVELTY_MODES)[number])) {
        return [`${slot}: offendingMode "${m}" — not a novelty mode`];
      }
      if (m === "rotation" && (mode.rotationSize ?? 0) < 2) {
        return [`${slot}: offendingField rotationSize — rotation needs ≥2`];
      }
      if (m === "batch_repeat" && (mode.maxConsecutiveSame ?? 0) < 1) {
        return [`${slot}: offendingField maxConsecutiveSame — batch_repeat needs ≥1`];
      }
      return [];
    },
  );

  const slotNovelty = (slot: string) => doc.noveltyTolerance?.bySlot?.[slot] ?? {};
  const setNovelty = (slot: string, p: Record<string, unknown>) =>
    patch({
      noveltyTolerance: {
        ...doc.noveltyTolerance,
        bySlot: {
          ...doc.noveltyTolerance?.bySlot,
          [slot]: { ...slotNovelty(slot), ...p },
        },
      },
    });

  const filteredAudit = auditFilter
    ? audit.filter((r) => r.fieldPath === auditFilter)
    : audit;

  return (
    <div className="mp-card side-card" id="lifestyle">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Lifestyle
        </span>
        <span className="intake-meta">
          set during onboarding, stable for months
        </span>
      </div>

      {dto.lastReviewPromptAt && (
        <div className="advisor-panel mp-card" style={{ marginTop: 12 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span className="advisor-dot" />
            <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
              Review nudge
            </span>
          </div>
          <div style={{ marginTop: 6 }}>
            <span className="mp-serif" style={{ fontSize: 19 }}>
              It's been a while — is this still how you eat?
            </span>
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
            <button className="btn btn-small btn-primary" onClick={markLifestyleReviewed}>
              Looks right
            </button>
            <button
              className="btn btn-small"
              onClick={() => {
                document.getElementById("lifestyle-groups")?.scrollIntoView({ block: "start" });
              }}
            >
              Update
            </button>
          </div>
        </div>
      )}

      <div id="lifestyle-groups" style={{ marginTop: 12 }}>
        {/* Meal structure */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.mealStructure}</summary>
          <div style={{ marginTop: 10 }}>
            {(["weekday", "weekend"] as const).map((dayType) => (
              <div key={dayType} className="pref-chip-block">
                <span className="mp-label">{dayType}</span>
                <ChipEditor
                  tint
                  values={doc.mealStructure?.[dayType]?.meals ?? []}
                  placeholder={`Add a ${dayType} meal`}
                  onChange={(meals) =>
                    patch({
                      mealStructure: {
                        ...doc.mealStructure,
                        [dayType]: { ...doc.mealStructure?.[dayType], meals },
                      },
                    })
                  }
                />
                <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 6, fontSize: 13 }}>
                  <input
                    type="checkbox"
                    checked={doc.mealStructure?.[dayType]?.snacks?.planned ?? false}
                    onChange={(e) =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          [dayType]: {
                            ...doc.mealStructure?.[dayType],
                            snacks: {
                              ...doc.mealStructure?.[dayType]?.snacks,
                              planned: e.target.checked,
                            },
                          },
                        },
                      })
                    }
                  />
                  planned snacks
                  <input
                    className="text-input"
                    style={{ flex: 1 }}
                    placeholder="snack style"
                    value={doc.mealStructure?.[dayType]?.snacks?.style ?? ""}
                    aria-label={`${dayType} snack style`}
                    onChange={(e) =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          [dayType]: {
                            ...doc.mealStructure?.[dayType],
                            snacks: {
                              planned: doc.mealStructure?.[dayType]?.snacks?.planned ?? false,
                              ...doc.mealStructure?.[dayType]?.snacks,
                              style: e.target.value || null,
                            },
                          },
                        },
                      })
                    }
                  />
                </label>
              </div>
            ))}
            <div className="pref-chip-block">
              <span className="mp-label">Recurring skips</span>
              {(doc.mealStructure?.recurringSkips ?? []).map((skip, i) => (
                <div key={i} className="exception-row" style={{ marginTop: 6 }}>
                  <input
                    className="text-input"
                    value={skip.day ?? ""}
                    placeholder="day"
                    aria-label={`Skip ${i + 1} day`}
                    onChange={(e) =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          recurringSkips: (doc.mealStructure?.recurringSkips ?? []).map((x, xi) =>
                            xi === i ? { ...x, day: e.target.value || null } : x,
                          ),
                        },
                      })
                    }
                  />
                  <input
                    className="text-input"
                    value={skip.meal ?? ""}
                    placeholder="meal"
                    aria-label={`Skip ${i + 1} meal`}
                    onChange={(e) =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          recurringSkips: (doc.mealStructure?.recurringSkips ?? []).map((x, xi) =>
                            xi === i ? { ...x, meal: e.target.value || null } : x,
                          ),
                        },
                      })
                    }
                  />
                  <input
                    className="text-input"
                    value={skip.reason ?? ""}
                    placeholder="reason"
                    aria-label={`Skip ${i + 1} reason`}
                    onChange={(e) =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          recurringSkips: (doc.mealStructure?.recurringSkips ?? []).map((x, xi) =>
                            xi === i ? { ...x, reason: e.target.value || null } : x,
                          ),
                        },
                      })
                    }
                  />
                  <button
                    className="btn btn-small"
                    aria-label={`Remove skip ${i + 1}`}
                    onClick={() =>
                      patch({
                        mealStructure: {
                          ...doc.mealStructure,
                          recurringSkips: (doc.mealStructure?.recurringSkips ?? []).filter(
                            (_, xi) => xi !== i,
                          ),
                        },
                      })
                    }
                  >
                    ✕
                  </button>
                </div>
              ))}
              <button
                className="btn btn-small"
                style={{ marginTop: 6 }}
                onClick={() =>
                  patch({
                    mealStructure: {
                      ...doc.mealStructure,
                      recurringSkips: [
                        ...(doc.mealStructure?.recurringSkips ?? []),
                        { day: null, meal: null, reason: null },
                      ],
                    },
                  })
                }
              >
                + skip
              </button>
            </div>
          </div>
        </details>

        {/* Meal timing */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.mealTiming}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.entries(doc.mealTiming?.preferredSchedule?.times ?? {}).map(
              ([slot, range]) => (
                <div key={slot} className="target-row">
                  <span className="target-label" style={{ textTransform: "capitalize" }}>
                    {slot}
                  </span>
                  <input
                    className="text-input"
                    style={{ width: 140 }}
                    value={range}
                    placeholder="HH:MM-HH:MM"
                    aria-label={`${slot} window`}
                    onChange={(e) =>
                      patch({
                        mealTiming: {
                          ...doc.mealTiming,
                          preferredSchedule: {
                            times: {
                              ...doc.mealTiming?.preferredSchedule?.times,
                              [slot]: e.target.value,
                            },
                          },
                        },
                      })
                    }
                  />
                </div>
              ),
            )}
            <label style={{ display: "block", marginTop: 8 }}>
              <span className="field-label">Flexibility</span>
              <input
                className="text-input"
                style={{ width: "100%" }}
                value={doc.mealTiming?.flexibility ?? ""}
                aria-label="Timing flexibility"
                onChange={(e) =>
                  patch({
                    mealTiming: { ...doc.mealTiming, flexibility: e.target.value || null },
                  })
                }
              />
            </label>
          </div>
        </details>

        {/* Novelty tolerance */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.noveltyTolerance}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.keys(doc.noveltyTolerance?.bySlot ?? {}).map((slot) => {
              const mode = slotNovelty(slot);
              return (
                <div key={slot} className="exception-row" style={{ marginTop: 6 }}>
                  <span className="target-label" style={{ textTransform: "capitalize", minWidth: 70 }}>
                    {slot}
                  </span>
                  <select
                    className="text-input time-select"
                    value={mode.mode ?? "rotation"}
                    aria-label={`${slot} novelty mode`}
                    onChange={(e) => setNovelty(slot, { mode: e.target.value })}
                  >
                    {NOVELTY_MODES.map((m) => (
                      <option key={m} value={m}>
                        {m}
                      </option>
                    ))}
                  </select>
                  {mode.mode === "rotation" && (
                    <input
                      type="number"
                      className="text-input num-input"
                      value={mode.rotationSize ?? ""}
                      placeholder="size"
                      aria-label={`${slot} rotation size`}
                      onChange={(e) =>
                        setNovelty(slot, { rotationSize: numOrUndef(e.target.value) ?? null })
                      }
                    />
                  )}
                  {mode.mode === "batch_repeat" && (
                    <input
                      type="number"
                      className="text-input num-input"
                      value={mode.maxConsecutiveSame ?? ""}
                      placeholder="max same"
                      aria-label={`${slot} max consecutive`}
                      onChange={(e) =>
                        setNovelty(slot, { maxConsecutiveSame: numOrUndef(e.target.value) ?? null })
                      }
                    />
                  )}
                  {mode.mode === "high_variety" && (
                    <>
                      <input
                        type="number"
                        className="text-input num-input"
                        value={mode.weeklyUniqueMinimum ?? ""}
                        placeholder="unique/wk"
                        aria-label={`${slot} weekly unique minimum`}
                        onChange={(e) =>
                          setNovelty(slot, {
                            weeklyUniqueMinimum: numOrUndef(e.target.value) ?? null,
                          })
                        }
                      />
                      <input
                        type="number"
                        className="text-input num-input"
                        value={mode.newPerWeek ?? ""}
                        placeholder="new/wk"
                        aria-label={`${slot} new per week`}
                        onChange={(e) =>
                          setNovelty(slot, { newPerWeek: numOrUndef(e.target.value) ?? null })
                        }
                      />
                    </>
                  )}
                </div>
              );
            })}
            {noveltyErrors.length > 0 && (
              <div className="rf-errors" role="alert" style={{ marginTop: 8 }}>
                {noveltyErrors.map((e) => (
                  <div key={e}>· {e}</div>
                ))}
              </div>
            )}
            <div className="pref-chip-block">
              <span className="mp-label">Repeat cooldown (weeks)</span>
              {Object.entries(doc.noveltyTolerance?.recipeRepeatCooldownWeeks ?? {}).map(
                ([slot, weeks]) => (
                  <div key={slot} className="target-row">
                    <span className="target-label" style={{ textTransform: "capitalize" }}>
                      {slot}
                    </span>
                    <input
                      type="number"
                      className="text-input num-input"
                      value={weeks}
                      min={0}
                      aria-label={`${slot} cooldown weeks`}
                      onChange={(e) =>
                        patch({
                          noveltyTolerance: {
                            ...doc.noveltyTolerance,
                            recipeRepeatCooldownWeeks: {
                              ...doc.noveltyTolerance?.recipeRepeatCooldownWeeks,
                              [slot]: Number(e.target.value) || 0,
                            },
                          },
                        })
                      }
                    />
                  </div>
                ),
              )}
            </div>
            <div className="pref-chip-block">
              <span className="mp-label">Ingredient frequency caps</span>
              {Object.entries(doc.noveltyTolerance?.ingredientFrequencyCaps ?? {}).map(
                ([key, cap]) => (
                  <div key={key} className="target-row">
                    <span className="target-label">{key}</span>
                    <input
                      className="text-input"
                      style={{ width: 110 }}
                      value={cap}
                      aria-label={`${key} cap`}
                      onChange={(e) =>
                        patch({
                          noveltyTolerance: {
                            ...doc.noveltyTolerance,
                            ingredientFrequencyCaps: {
                              ...doc.noveltyTolerance?.ingredientFrequencyCaps,
                              [key]: e.target.value,
                            },
                          },
                        })
                      }
                    />
                  </div>
                ),
              )}
            </div>
          </div>
        </details>

        {/* Cooking contexts */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.cookingContexts}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.entries(doc.cookingContexts?.byContext ?? {}).map(([name, ctx]) => (
              <div key={name} className="pref-chip-block">
                <span className="mp-label">{name}</span>
                <div className="exception-row" style={{ marginTop: 6 }}>
                  <input
                    type="number"
                    className="text-input num-input"
                    value={ctx.maxTimeMins ?? ""}
                    placeholder="max min"
                    aria-label={`${name} max minutes`}
                    onChange={(e) =>
                      patch({
                        cookingContexts: {
                          byContext: {
                            ...doc.cookingContexts?.byContext,
                            [name]: { ...ctx, maxTimeMins: numOrUndef(e.target.value) },
                          },
                        },
                      })
                    }
                  />
                  <input
                    className="text-input"
                    value={ctx.complexity ?? ""}
                    placeholder="complexity"
                    aria-label={`${name} complexity`}
                    onChange={(e) =>
                      patch({
                        cookingContexts: {
                          byContext: {
                            ...doc.cookingContexts?.byContext,
                            [name]: { ...ctx, complexity: e.target.value || null },
                          },
                        },
                      })
                    }
                  />
                  <input
                    type="number"
                    className="text-input num-input"
                    value={ctx.preferredIngredientCount?.min ?? ""}
                    placeholder="min ing"
                    aria-label={`${name} min ingredients`}
                    onChange={(e) =>
                      patch({
                        cookingContexts: {
                          byContext: {
                            ...doc.cookingContexts?.byContext,
                            [name]: {
                              ...ctx,
                              preferredIngredientCount: {
                                min: numOrUndef(e.target.value) ?? 0,
                                max: ctx.preferredIngredientCount?.max ?? 0,
                              },
                            },
                          },
                        },
                      })
                    }
                  />
                  <input
                    type="number"
                    className="text-input num-input"
                    value={ctx.preferredIngredientCount?.max ?? ""}
                    placeholder="max ing"
                    aria-label={`${name} max ingredients`}
                    onChange={(e) =>
                      patch({
                        cookingContexts: {
                          byContext: {
                            ...doc.cookingContexts?.byContext,
                            [name]: {
                              ...ctx,
                              preferredIngredientCount: {
                                min: ctx.preferredIngredientCount?.min ?? 0,
                                max: numOrUndef(e.target.value) ?? 0,
                              },
                            },
                          },
                        },
                      })
                    }
                  />
                </div>
                <ChipEditor
                  tint
                  values={ctx.preferredStyles ?? []}
                  placeholder="Add a preferred style"
                  onChange={(preferredStyles) =>
                    patch({
                      cookingContexts: {
                        byContext: {
                          ...doc.cookingContexts?.byContext,
                          [name]: { ...ctx, preferredStyles },
                        },
                      },
                    })
                  }
                />
              </div>
            ))}
          </div>
        </details>

        {/* Batch cooking */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.batchCooking}</summary>
          <div style={{ marginTop: 10 }}>
            {(doc.batchCooking?.prepDays ?? []).map((day, i) => (
              <div key={i} className="exception-row" style={{ marginTop: 6 }}>
                <input
                  className="text-input"
                  value={day.day ?? ""}
                  placeholder="day"
                  aria-label={`Prep day ${i + 1}`}
                  onChange={(e) =>
                    patch({
                      batchCooking: {
                        ...doc.batchCooking,
                        prepDays: (doc.batchCooking?.prepDays ?? []).map((x, xi) =>
                          xi === i ? { ...x, day: e.target.value || null } : x,
                        ),
                      },
                    })
                  }
                />
                <input
                  className="text-input"
                  value={day.window ?? ""}
                  placeholder="window"
                  aria-label={`Prep day ${i + 1} window`}
                  onChange={(e) =>
                    patch({
                      batchCooking: {
                        ...doc.batchCooking,
                        prepDays: (doc.batchCooking?.prepDays ?? []).map((x, xi) =>
                          xi === i ? { ...x, window: e.target.value || null } : x,
                        ),
                      },
                    })
                  }
                />
                <input
                  type="number"
                  className="text-input num-input"
                  value={day.maxSessionHours ?? ""}
                  placeholder="hours"
                  aria-label={`Prep day ${i + 1} max hours`}
                  onChange={(e) =>
                    patch({
                      batchCooking: {
                        ...doc.batchCooking,
                        prepDays: (doc.batchCooking?.prepDays ?? []).map((x, xi) =>
                          xi === i ? { ...x, maxSessionHours: numOrUndef(e.target.value) } : x,
                        ),
                      },
                    })
                  }
                />
                <input
                  type="number"
                  className="text-input num-input"
                  value={day.maxRecipes ?? ""}
                  placeholder="recipes"
                  aria-label={`Prep day ${i + 1} max recipes`}
                  onChange={(e) =>
                    patch({
                      batchCooking: {
                        ...doc.batchCooking,
                        prepDays: (doc.batchCooking?.prepDays ?? []).map((x, xi) =>
                          xi === i ? { ...x, maxRecipes: numOrUndef(e.target.value) } : x,
                        ),
                      },
                    })
                  }
                />
              </div>
            ))}
            <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 8, fontSize: 13 }}>
              <input
                type="checkbox"
                checked={doc.batchCooking?.freezerTolerance?.acceptable ?? false}
                onChange={(e) =>
                  patch({
                    batchCooking: {
                      ...doc.batchCooking,
                      freezerTolerance: {
                        ...doc.batchCooking?.freezerTolerance,
                        acceptable: e.target.checked,
                      },
                    },
                  })
                }
              />
              freezer ok · max
              <input
                type="number"
                className="text-input num-input"
                value={doc.batchCooking?.freezerTolerance?.maxFrozenMealsPerWeek ?? ""}
                aria-label="Max frozen meals per week"
                onChange={(e) =>
                  patch({
                    batchCooking: {
                      ...doc.batchCooking,
                      freezerTolerance: {
                        ...doc.batchCooking?.freezerTolerance,
                        maxFrozenMealsPerWeek: numOrUndef(e.target.value),
                      },
                    },
                  })
                }
              />
              frozen meals/week
            </label>
            <div className="pref-chip-block">
              <span className="mp-label">Freezer exclusions</span>
              <ChipEditor
                values={doc.batchCooking?.freezerTolerance?.exclusions ?? []}
                placeholder="Add an exclusion"
                onChange={(exclusions) =>
                  patch({
                    batchCooking: {
                      ...doc.batchCooking,
                      freezerTolerance: { ...doc.batchCooking?.freezerTolerance, exclusions },
                    },
                  })
                }
              />
            </div>
            <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 8, fontSize: 13 }}>
              <input
                type="checkbox"
                checked={doc.batchCooking?.sameProteinSameDay ?? false}
                onChange={(e) =>
                  patch({
                    batchCooking: { ...doc.batchCooking, sameProteinSameDay: e.target.checked },
                  })
                }
              />
              same protein twice in a day is fine
            </label>
          </div>
        </details>

        {/* Reheating */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.reheatingPreferences}</summary>
          <div style={{ marginTop: 10 }}>
            <div className="pref-chip-block">
              <span className="mp-label">At work</span>
              <ChipEditor
                tint
                values={doc.reheatingPreferences?.availableAtWork ?? []}
                placeholder="Add equipment"
                onChange={(availableAtWork) =>
                  patch({
                    reheatingPreferences: { ...doc.reheatingPreferences, availableAtWork },
                  })
                }
              />
            </div>
            <div className="pref-chip-block">
              <span className="mp-label">At home</span>
              <ChipEditor
                tint
                values={doc.reheatingPreferences?.availableAtHome ?? []}
                placeholder="Add equipment"
                onChange={(availableAtHome) =>
                  patch({
                    reheatingPreferences: { ...doc.reheatingPreferences, availableAtHome },
                  })
                }
              />
            </div>
            {(doc.reheatingPreferences?.exclusions ?? []).map((rule, i) => (
              <div key={i} className="exception-row" style={{ marginTop: 6 }}>
                <input
                  className="text-input"
                  value={rule.category ?? ""}
                  placeholder="category"
                  aria-label={`Reheat rule ${i + 1} category`}
                  onChange={(e) =>
                    patch({
                      reheatingPreferences: {
                        ...doc.reheatingPreferences,
                        exclusions: (doc.reheatingPreferences?.exclusions ?? []).map((x, xi) =>
                          xi === i ? { ...x, category: e.target.value || null } : x,
                        ),
                      },
                    })
                  }
                />
                <input
                  className="text-input"
                  value={rule.rule ?? ""}
                  placeholder="rule"
                  aria-label={`Reheat rule ${i + 1} rule`}
                  onChange={(e) =>
                    patch({
                      reheatingPreferences: {
                        ...doc.reheatingPreferences,
                        exclusions: (doc.reheatingPreferences?.exclusions ?? []).map((x, xi) =>
                          xi === i ? { ...x, rule: e.target.value || null } : x,
                        ),
                      },
                    })
                  }
                />
                <input
                  className="text-input"
                  value={rule.reason ?? ""}
                  placeholder="reason"
                  aria-label={`Reheat rule ${i + 1} reason`}
                  onChange={(e) =>
                    patch({
                      reheatingPreferences: {
                        ...doc.reheatingPreferences,
                        exclusions: (doc.reheatingPreferences?.exclusions ?? []).map((x, xi) =>
                          xi === i ? { ...x, reason: e.target.value || null } : x,
                        ),
                      },
                    })
                  }
                />
                <button
                  className="btn btn-small"
                  aria-label={`Remove reheat rule ${i + 1}`}
                  onClick={() =>
                    patch({
                      reheatingPreferences: {
                        ...doc.reheatingPreferences,
                        exclusions: (doc.reheatingPreferences?.exclusions ?? []).filter(
                          (_, xi) => xi !== i,
                        ),
                      },
                    })
                  }
                >
                  ✕
                </button>
              </div>
            ))}
            <div className="pref-chip-block">
              <span className="mp-label">Fine cold</span>
              <ChipEditor
                values={doc.reheatingPreferences?.coldMealTolerance ?? []}
                placeholder="Add a cold-tolerant meal"
                onChange={(coldMealTolerance) =>
                  patch({
                    reheatingPreferences: { ...doc.reheatingPreferences, coldMealTolerance },
                  })
                }
              />
            </div>
          </div>
        </details>

        {/* Eating context */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.eatingContext}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.entries(doc.eatingContext?.bySlot ?? {}).map(([slot, ctx]) => (
              <div key={slot} className="exception-row" style={{ marginTop: 6 }}>
                <span className="target-label" style={{ textTransform: "capitalize", minWidth: 70 }}>
                  {slot}
                </span>
                <input
                  className="text-input"
                  value={ctx.location ?? ""}
                  placeholder="location"
                  aria-label={`${slot} location`}
                  onChange={(e) =>
                    patch({
                      eatingContext: {
                        bySlot: {
                          ...doc.eatingContext?.bySlot,
                          [slot]: { ...ctx, location: e.target.value || null },
                        },
                      },
                    })
                  }
                />
                <input
                  className="text-input"
                  value={ctx.format ?? ""}
                  placeholder="format"
                  aria-label={`${slot} format`}
                  onChange={(e) =>
                    patch({
                      eatingContext: {
                        bySlot: {
                          ...doc.eatingContext?.bySlot,
                          [slot]: { ...ctx, format: e.target.value || null },
                        },
                      },
                    })
                  }
                />
              </div>
            ))}
          </div>
        </details>

        {/* Seasonal */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.seasonalPreferences}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.entries(doc.seasonalPreferences?.bySeason ?? {}).map(([season, policy]) => (
              <div key={season} className="pref-chip-block">
                <span className="mp-label">{season}</span>
                <div style={{ display: "grid", gap: 6, marginTop: 6 }}>
                  <ChipEditor
                    tint
                    values={policy.leanToward ?? []}
                    placeholder="Lean toward…"
                    onChange={(leanToward) =>
                      patch({
                        seasonalPreferences: {
                          bySeason: {
                            ...doc.seasonalPreferences?.bySeason,
                            [season]: { ...policy, leanToward },
                          },
                        },
                      })
                    }
                  />
                  <ChipEditor
                    values={policy.avoid ?? []}
                    placeholder="Avoid…"
                    onChange={(avoid) =>
                      patch({
                        seasonalPreferences: {
                          bySeason: {
                            ...doc.seasonalPreferences?.bySeason,
                            [season]: { ...policy, avoid },
                          },
                        },
                      })
                    }
                  />
                </div>
              </div>
            ))}
          </div>
        </details>

        {/* Meal-type */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.mealTypePreferences}</summary>
          <div style={{ marginTop: 10 }}>
            {Object.entries(doc.mealTypePreferences?.byType ?? {}).map(([type, rule]) => (
              <div key={type} className="pref-chip-block">
                <span className="mp-label">{type}</span>
                <div className="exception-row" style={{ marginTop: 6 }}>
                  <input
                    className="text-input"
                    value={rule.varietyTolerance ?? ""}
                    placeholder="variety tolerance"
                    aria-label={`${type} variety tolerance`}
                    onChange={(e) =>
                      patch({
                        mealTypePreferences: {
                          byType: {
                            ...doc.mealTypePreferences?.byType,
                            [type]: { ...rule, varietyTolerance: e.target.value || null },
                          },
                        },
                      })
                    }
                  />
                  <input
                    className="text-input"
                    value={rule.complexityTolerance ?? ""}
                    placeholder="complexity tolerance"
                    aria-label={`${type} complexity tolerance`}
                    onChange={(e) =>
                      patch({
                        mealTypePreferences: {
                          byType: {
                            ...doc.mealTypePreferences?.byType,
                            [type]: { ...rule, complexityTolerance: e.target.value || null },
                          },
                        },
                      })
                    }
                  />
                </div>
                <ChipEditor
                  tint
                  values={rule.staples ?? []}
                  placeholder="Add a staple"
                  onChange={(staples) =>
                    patch({
                      mealTypePreferences: {
                        byType: {
                          ...doc.mealTypePreferences?.byType,
                          [type]: { ...rule, staples },
                        },
                      },
                    })
                  }
                />
              </div>
            ))}
          </div>
        </details>

        {/* Accompaniments */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.accompaniments}</summary>
          <div style={{ marginTop: 10 }}>
            <div className="rf-grid2">
              <label>
                <span className="field-label">With meals</span>
                <input
                  className="text-input"
                  value={doc.accompaniments?.beverages?.withMeals ?? ""}
                  aria-label="Beverages with meals"
                  onChange={(e) =>
                    patch({
                      accompaniments: {
                        ...doc.accompaniments,
                        beverages: {
                          ...doc.accompaniments?.beverages,
                          withMeals: e.target.value || null,
                        },
                      },
                    })
                  }
                />
              </label>
              <label>
                <span className="field-label">Morning</span>
                <input
                  className="text-input"
                  value={doc.accompaniments?.beverages?.morning ?? ""}
                  aria-label="Morning beverages"
                  onChange={(e) =>
                    patch({
                      accompaniments: {
                        ...doc.accompaniments,
                        beverages: {
                          ...doc.accompaniments?.beverages,
                          morning: e.target.value || null,
                        },
                      },
                    })
                  }
                />
              </label>
            </div>
            <div className="pref-chip-block">
              <span className="mp-label">Avoids</span>
              <ChipEditor
                values={doc.accompaniments?.beverages?.avoids ?? []}
                placeholder="Add a drink to avoid"
                onChange={(avoids) =>
                  patch({
                    accompaniments: {
                      ...doc.accompaniments,
                      beverages: { ...doc.accompaniments?.beverages, avoids },
                    },
                  })
                }
              />
            </div>
            <label style={{ display: "block", marginTop: 8 }}>
              <span className="field-label">Sides</span>
              <input
                className="text-input"
                style={{ width: "100%" }}
                value={doc.accompaniments?.sides?.notes ?? ""}
                aria-label="Sides notes"
                onChange={(e) =>
                  patch({
                    accompaniments: {
                      ...doc.accompaniments,
                      sides: { notes: e.target.value || null },
                    },
                  })
                }
              />
            </label>
          </div>
        </details>

        {/* Grocery quality */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.groceryQualityPreferences}</summary>
          <div style={{ marginTop: 10 }}>
            {(
              [
                ["organic", "Organic"],
                ["freeRangeEggs", "Free-range eggs"],
                ["freeRangeMeat", "Free-range meat"],
                ["brandedVsOwnLabel", "Branded vs own label"],
              ] as const
            ).map(([key, label]) => (
              <div key={key} className="target-row">
                <span className="target-label">{label}</span>
                <select
                  className="text-input time-select"
                  value={doc.groceryQualityPreferences?.[key] ?? "no_preference"}
                  aria-label={label}
                  onChange={(e) =>
                    patch({
                      groceryQualityPreferences: {
                        ...doc.groceryQualityPreferences,
                        [key]: e.target.value,
                      },
                    })
                  }
                >
                  {QUALITY_OPTIONS.map((o) => (
                    <option key={o} value={o}>
                      {o.replace(/_/g, " ")}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        </details>

        {/* Pantry tracking */}
        <details className="micros-details">
          <summary>{GROUP_LABEL.pantryTracking}</summary>
          <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 10, fontSize: 13 }}>
            <input
              type="checkbox"
              checked={doc.pantryTracking?.enabled ?? false}
              onChange={(e) => patch({ pantryTracking: { enabled: e.target.checked } })}
            />
            pantry deductions on — gates provisions behaviour
          </label>
        </details>
      </div>

      {dirty && (
        <div className="modal-actions" style={{ marginTop: 14 }}>
          <button className="btn" onClick={() => setDraft(null)}>
            Discard changes
          </button>
          <button
            className="btn btn-primary"
            disabled={noveltyErrors.length > 0}
            title={noveltyErrors.length > 0 ? "Fix the novelty rows first (server 400s them)" : undefined}
            onClick={() => {
              const outcome = saveLifestyleConfig({
                document: doc,
                expectedVersion: dto.optimisticVersion,
              });
              if (outcome === "ok") setDraft(null);
            }}
          >
            Save lifestyle config
          </button>
        </div>
      )}

      <details className="micros-details" style={{ marginTop: 14 }}>
        <summary>Change history ({audit.length})</summary>
        <div className="filter-row" style={{ marginTop: 8 }}>
          <button
            className={`filter-chip${auditFilter === null ? " active" : ""}`}
            onClick={() => setAuditFilter(null)}
          >
            all
          </button>
          {LIFESTYLE_GROUPS.filter((g) => audit.some((r) => r.fieldPath === g)).map((g) => (
            <button
              key={g}
              className={`filter-chip${auditFilter === g ? " active" : ""}`}
              onClick={() => setAuditFilter(g)}
            >
              {GROUP_LABEL[g].toLowerCase()}
            </button>
          ))}
        </div>
        <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
          {filteredAudit.map((row) => (
            <div key={row.id} className="history-row">
              <div style={{ minWidth: 0 }}>
                <div className="history-query">{row.fieldPath}</div>
                <div className="history-meta">{shortWhen(row.occurredAt)}</div>
                {rawAuditId === row.id && (
                  <pre className="raw-json">
                    {JSON.stringify({ from: row.previousValueJson, to: row.newValueJson }, null, 2)}
                  </pre>
                )}
              </div>
              <button
                className="link-btn"
                onClick={() => setRawAuditId(rawAuditId === row.id ? null : row.id)}
              >
                {rawAuditId === row.id ? "hide diff" : "diff"}
              </button>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}

/* ================= archive (§6) ===================================================== */

const FIELD_PATH_LABEL: Record<string, string> = {
  "ingredientPreferences.favourites": "ingredient favourites",
  "ingredientPreferences.disliked": "ingredient dislikes",
  "cuisinePreferences.favourites": "cuisine favourites",
  "cuisinePreferences.enjoys": "cuisines enjoyed",
  recipesToRepeat: "recipes to repeat",
  recipesToAvoid: "recipes to avoid",
};

const ARCHIVE_FILTERS = [
  { key: null, label: "all" },
  { key: "ingredientPreferences", label: "ingredients" },
  { key: "cuisinePreferences", label: "cuisines" },
  { key: "recipes", label: "recipes" },
] as const;

function ArchiveSection() {
  const archive = useStore((s) => s.preferences.archive);
  const activeCount = useStore(selectArchiveActiveCount);
  const [filter, setFilter] = useState<string | null>(null);
  const [rawId, setRawId] = useState<string | null>(null);

  const rows = filter
    ? archive.filter((a) => a.fieldPath.startsWith(filter))
    : archive;

  return (
    <details className="micros-details" style={{ marginTop: 22 }}>
      <summary>Archive ({activeCount})</summary>
      <div className="filter-row" style={{ marginTop: 8 }}>
        {ARCHIVE_FILTERS.map((f) => (
          <button
            key={f.label}
            className={`filter-chip${filter === f.key ? " active" : ""}`}
            onClick={() => setFilter(f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>
      <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
        {rows.map((row) => (
          <div
            key={row.id}
            className="history-row"
            style={row.rePromotedAt ? { opacity: 0.6 } : undefined}
          >
            <div style={{ minWidth: 0 }}>
              <div className="history-query">
                {row.itemKey}
                <span className="route-conf" style={{ marginLeft: 8 }}>
                  {FIELD_PATH_LABEL[row.fieldPath] ?? row.fieldPath} · ×{row.evidenceCount}
                </span>
              </div>
              <div className="history-meta">
                {row.lastSignalAt && `last signal ${shortWhen(row.lastSignalAt)} · `}
                archived {shortWhen(row.archivedAt)}
              </div>
              {rawId === row.id && (
                <pre className="raw-json">{JSON.stringify(row.itemPayload, null, 2)}</pre>
              )}
            </div>
            <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
              {row.rePromotedAt ? (
                <TintChip>re-promoted ✓</TintChip>
              ) : row.archivedReason === "LOW_EVIDENCE" ? (
                <span className="mp-chip muted">not enough signal</span>
              ) : row.archivedReason === "STALE" ? (
                <span className="mp-chip muted">no recent signal</span>
              ) : (
                <span className="mp-chip muted">made room</span>
              )}
              <button className="link-btn" onClick={() => setRawId(rawId === row.id ? null : row.id)}>
                raw
              </button>
            </div>
          </div>
        ))}
      </div>
      <div className="grocery-footnote" style={{ marginTop: 10 }}>
        Pruned, not deleted — these come back by themselves if your feedback
        re-supports them. Read-only by design.
      </div>
    </details>
  );
}

/* ================= the page ========================================================= */

export function Preferences() {
  const tp = useStore((s) => s.preferences.tasteProfile);
  const versions = useStore((s) => s.preferences.versions);
  const tasteAudit = useStore((s) => s.preferences.tasteAudit);
  const refreshing = useStore((s) => s.preferences.refreshing);

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<TasteProfileDocument | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [noChangeNote, setNoChangeNote] = useState(false);
  const preRefreshVersion = useRef<number | null>(null);

  // Poll outcome: the contract has no completion signal — when the fake 202
  // settles without a documentVersion bump, surface the three-event rule.
  useEffect(() => {
    if (refreshing) {
      setNoChangeNote(false);
      return;
    }
    if (preRefreshVersion.current !== null && tp) {
      if (tp.documentVersion === preRefreshVersion.current) setNoChangeNote(true);
      preRefreshVersion.current = null;
    }
  }, [refreshing, tp]);

  if (!tp) {
    return (
      <div>
        <PageHeader title="Taste & preferences" meta="" />
        <div className="page-loading">Finish onboarding to start here.</div>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Taste & preferences"
        meta="What the advisor has learned, your hard constraints, and lifestyle configuration"
        actions={
          <>
            <button
              className="btn"
              onClick={() => setDrawerOpen(true)}
              disabled={refreshing}
            >
              Roll back
            </button>
            <button
              className="btn"
              onClick={() => {
                if (editing) {
                  setEditing(false);
                  setDraft(null);
                } else {
                  setDraft(clone(tp.document));
                  setEditing(true);
                }
              }}
            >
              {editing ? "Cancel edit" : "Edit profile"}
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                preRefreshVersion.current = tp.documentVersion;
                refreshTasteProfile();
              }}
              disabled={refreshing || editing}
            >
              {refreshing ? "Refreshing…" : "Refresh now"}
            </button>
          </>
        }
      />

      <div className="version-strip">
        <span className="version-current">v{tp.documentVersion} current</span>
        {tp.tasteVectorStatus === "PENDING" && (
          <span className="mp-chip amber" title="updating taste matching">
            ◌ taste matching updating
          </span>
        )}
        {tp.tasteVectorStatus === "FAILED" && (
          <span className="mp-chip amber" title="taste matching degraded — retries on next update">
            ● taste matching degraded
          </span>
        )}
        <span className="version-note">
          last learned{" "}
          {tp.lastDeltaAppliedAt ? shortWhen(tp.lastDeltaAppliedAt) : "— nothing learned yet"}
        </span>
        <span style={{ flex: 1 }} />
      </div>

      <div style={{ marginTop: 18 }}>
        <span className="mp-serif" style={{ fontSize: 23 }}>
          Here's what I think you like — built from {tp.basedOnFeedbackCount}{" "}
          feedback signals. Correct anything that's wrong.
        </span>
      </div>

      {refreshing && (
        <div className="inline-note" style={{ marginTop: 10 }}>
          Refresh accepted (202) — polling for a new version. There's no
          completion signal in the contract, so this poll times out quietly
          (spec §8 Q2).
        </div>
      )}
      {noChangeNote && (
        <div className="inline-note" style={{ marginTop: 10 }}>
          No changes yet — one-off comments may not move the profile (it waits
          for 2–3 agreeing signals).{" "}
          <button className="link-btn" onClick={() => setNoChangeNote(false)}>
            dismiss
          </button>
        </div>
      )}

      {editing && draft ? (
        <>
          <TasteDocEditor draft={draft} setDraft={setDraft} />
          <div className="modal-actions" style={{ marginTop: 16 }}>
            <button
              className="btn"
              onClick={() => {
                setEditing(false);
                setDraft(null);
              }}
            >
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                const outcome = saveTasteProfile(draft, tp.optimisticVersion);
                if (outcome === "ok") {
                  setEditing(false);
                  setDraft(null);
                }
              }}
            >
              Save manual override
            </button>
          </div>
          <div className="grocery-footnote" style={{ marginTop: 8 }}>
            Saves the full document with expectedVersion {tp.optimisticVersion};
            server-managed scalars are echoed back verbatim (spec §8 Q1). The
            override is flagged so the advisor won't re-learn it.
          </div>
        </>
      ) : (
        <TasteDocCards doc={tp.document} />
      )}

      <details className="micros-details" style={{ marginTop: 18 }}>
        <summary>Change history ({tasteAudit.length})</summary>
        <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
          {tasteAudit.map((row) => (
            <div key={row.id} className="history-row">
              <div style={{ minWidth: 0 }}>
                <div className="history-query">
                  {row.previousDocumentVersion != null
                    ? `v${row.previousDocumentVersion} → v${row.newDocumentVersion}`
                    : `v${row.newDocumentVersion}`}
                  {row.summary && (
                    <span className="route-conf" style={{ marginLeft: 8 }}>
                      {row.summary}
                    </span>
                  )}
                </div>
                <div className="history-meta">
                  {row.actorType === "USER" ? "you" : row.actorType === "AI" ? "advisor" : "system"}{" "}
                  · {shortWhen(row.occurredAt)}
                </div>
              </div>
              <span
                className={`mp-chip${row.changeType === "AI_DELTA_APPLIED" ? "" : " muted"}`}
              >
                {CHANGE_TYPE_LABEL[row.changeType]}
              </span>
            </div>
          ))}
        </div>
      </details>

      <div className="pref-bottom">
        <HardConstraintsSection />
        <LifestyleSection />
      </div>

      <ArchiveSection />

      {drawerOpen && (
        <VersionsDrawer
          versions={versions}
          currentVersion={tp.documentVersion}
          optimisticVersion={tp.optimisticVersion}
          onClose={() => setDrawerOpen(false)}
        />
      )}
    </div>
  );
}
