/**
 * Recipe detail — rebuilt against the contract-complete page spec
 * (design/frontend/pages/recipe-detail.md): hero + body on RecipeDto /
 * RecipeVersionDto, branch strip + version history with structured diffs and
 * revert, the substitutions state machine, multi-dimensional ratings (taste ·
 * effortWorthIt · portionFit · repeatValue, 0–100), import provenance, image
 * upload, nutrition recalculation, and the per-recipe adaptation slice.
 */

import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { LIVE } from "../live/flag";
import { hydrateRecipeDetail } from "../live/hydrate";
import { MOCK_NOW_MS } from "../live/dates";
import { Modal } from "../components/Modal";
import { SegmentBar } from "../components/SegmentBar";
import { TintChip } from "../components/TintChip";
import {
  computeDiff,
  computeRatingSummary,
  requestFromVersion,
  titleCase,
  versionWithSubstitutions,
} from "../mock/recipeLogic";
import {
  acceptPendingChange,
  actOnSubstitution,
  archiveRecipe,
  createVariantBranch,
  deleteRating,
  demoteRecipe,
  diffFromProposed,
  editRecipe,
  promoteRecipe,
  proposeSubstitution,
  pushToast,
  recalculateNutrition,
  rejectPendingChange,
  revertRecipe,
  setRecipeImage,
  submitRating,
  unarchiveRecipe,
  updateRating,
  useStore,
  versionsFor,
} from "../mock/store";
import type {
  PendingChangeDto,
  RecipeDiffDto,
  RecipeDto,
  RecipeNutritionResultDto,
  RecipeSubstitutionDto,
  RecipeVersionDto,
  StoreState,
  SubstitutionReason,
} from "../mock/types";
import {
  DiffView,
  fmtIngredient,
  needsReviewCountOf,
  NutritionStatusNote,
  QualityBadge,
  qtyStr,
  RecipeForm,
  shortWhen,
} from "./recipes/shared";

const MOCK_TODAY_MS = MOCK_NOW_MS;

const TRIGGER_LABEL: Record<RecipeVersionDto["trigger"], string> = {
  MANUAL_CREATE: "created",
  MANUAL_EDIT: "edited by you",
  IMPORT: "imported",
  ADAPTATION_PIPELINE: "AI adaptation",
  SUBSTITUTION_PROMOTION: "swap made permanent",
  BRANCH_CREATION: "branch start",
  REVERT: "revert",
};

const actorLabel = (actor: string): string =>
  actor.startsWith("user:") ? "you" : actor.includes("pipeline") || actor.includes("ai") ? "AI" : actor;

const DIMENSION_LABEL: Record<string, string> = {
  SALT_LEVEL: "salt level",
  PROTEIN: "protein",
  METHOD_SIMPLIFICATION: "method simplification",
  PORTION_SIZE: "portion size",
  FLAVOUR_BALANCE: "flavour balance",
  ACID_BALANCE: "acid balance",
  TEXTURE: "texture",
  COOKING_TIME: "cooking time",
  SUBSTITUTION_PROMOTION: "swap promotion",
  GENERAL: "general",
};

/* ================= hero ====================================================== */

function ImageControl({ recipe }: { recipe: RecipeDto }) {
  const fileRef = useRef<HTMLInputElement>(null);
  if (recipe.catalogue === "SYSTEM") return null; // 403 — owner only, never rendered
  const pick = (file: File | undefined) => {
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      pushToast("413 — image too large (max 5 MB)", "warn");
      return;
    }
    if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
      pushToast("415 — JPEG, PNG or WebP only (magic-byte probed server-side)", "warn");
      return;
    }
    setRecipeImage(recipe.id, URL.createObjectURL(file));
  };
  return (
    <>
      <input
        ref={fileRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        style={{ display: "none" }}
        aria-label="Upload recipe photo"
        onChange={(e) => pick(e.target.files?.[0])}
      />
      <button
        className={recipe.imageUrl ? "photo-replace btn btn-small" : "photo-dropzone"}
        onClick={() => fileRef.current?.click()}
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault();
          pick(e.dataTransfer.files?.[0]);
        }}
      >
        {recipe.imageUrl ? "Replace photo" : "Add a photo — drop or click (≤5 MB, JPEG/PNG/WebP)"}
      </button>
    </>
  );
}

function NutritionBand({
  result,
  recipeId,
  servings,
}: {
  result: RecipeNutritionResultDto | undefined;
  recipeId: string;
  servings: number | undefined;
}) {
  void recipeId;
  if (!result) {
    return (
      <div className="inline-note" style={{ marginTop: 10 }}>
        Per-serving nutrition is unwireable from the read contract —
        RecipeVersionDto carries no nutritionPerServing (spec §11 Q1, headline
        backend gap). Run “Recalculate nutrition” to compute it.
      </div>
    );
  }
  return (
    <div style={{ marginTop: 12 }}>
      <div className="detail-pills">
        <span className="nutrition-pill">{result.caloriesPerServing} kcal</span>
        <span className="nutrition-pill">{result.proteinPerServingG} g protein</span>
        <span className="nutrition-pill">{result.carbsPerServingG} g carbs</span>
        <span className="nutrition-pill">{result.fatPerServingG} g fat</span>
        <span className="nutrition-pill">{result.fibrePerServingG} g fibre</span>
        <span className="nutrition-pill-note">
          per serving{servings != null ? ` (of ${servings})` : ""} · from recalculate
        </span>
      </div>
      {result.unmapped.length > 0 && (
        <div className="import-warnings" style={{ marginTop: 8 }}>
          {result.unmapped.map((u) => (
            <div key={u.name}>
              ⚠ {u.name}: {u.reason} ({u.confidence.toFixed(2)}) —{" "}
              <Link to="/nutrition">fix in Data quality</Link>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/* ================= adaptation card (§10) ====================================== */

const NO_HISTORY: PendingChangeDto[] = [];

function AdaptationCard({ recipe }: { recipe: RecipeDto }) {
  const listItem = useStore((s) =>
    s.adaptation.pendingChanges.find((c) => c.recipeId === recipe.id),
  );
  const detail = useStore((s) =>
    listItem ? s.adaptation.detailById[listItem.id] : undefined,
  );
  // NOTE: selector must return a STORED reference (useSyncExternalStore);
  // `?? []` belongs outside it or every snapshot is a fresh array → loop.
  const historyStored = useStore((s) => s.adaptation.historyByRecipe[recipe.id]);
  const history = historyStored ?? NO_HISTORY;
  const [expanded, setExpanded] = useState(false);
  const [modify, setModify] = useState(false);
  const [editedQty, setEditedQty] = useState<string>("");
  const [rejecting, setRejecting] = useState(false);
  const [reasonNote, setReasonNote] = useState("");
  const [showHistory, setShowHistory] = useState(false);

  const baseDiff = detail ? diffFromProposed(detail) : null;

  if (!listItem && history.length === 0) return null;

  const expiresDays = listItem
    ? Math.max(0, Math.ceil((Date.parse(listItem.expiresAt) - MOCK_TODAY_MS) / 86_400_000))
    : 0;

  const buildUserEdits = (): RecipeDiffDto | null => {
    if (!baseDiff || !modify) return null;
    const qty = Number(editedQty);
    if (!Number.isFinite(qty)) return null;
    return {
      ...baseDiff,
      ingredientChanges: baseDiff.ingredientChanges.map((ch, i) =>
        i === 0 && ch.to ? { ...ch, to: { ...ch.to, quantity: qty } } : ch,
      ),
    };
  };

  return (
    <div className="advisor-panel mp-card">
      <div className="advisor-panel-head">
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            Suggested change · {listItem ? (DIMENSION_LABEL[listItem.changeDimension] ?? listItem.changeDimension) : "history"}
          </span>
        </div>
        {listItem && (
          <span className="advisor-panel-note">
            confidence {listItem.confidence.toFixed(2)} · expires in {expiresDays} day{expiresDays === 1 ? "" : "s"}
          </span>
        )}
      </div>
      {listItem && (
        <>
          <div style={{ marginTop: 8 }}>
            <span className="mp-serif" style={{ fontSize: 21 }}>
              {listItem.reasoningPreview ??
                `${DIMENSION_LABEL[listItem.changeDimension] ?? listItem.changeDimension} change suggested`}
            </span>
          </div>
          {!expanded ? (
            <div style={{ marginTop: 12 }}>
              <button className="btn btn-small" onClick={() => setExpanded(true)}>
                Review (fetch detail)
              </button>
            </div>
          ) : detail ? (
            <div style={{ marginTop: 10 }}>
              <div className="dialog-body" style={{ marginTop: 0 }}>
                {detail.reasoning}
              </div>
              {detail.nutritionalNotes && (
                <div className="inline-note" style={{ marginTop: 6 }}>
                  {detail.nutritionalNotes}
                </div>
              )}
              <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
                <span className="mp-chip">
                  {detail.proposedClassification === "VERSION"
                    ? "new version"
                    : detail.proposedClassification === "BRANCH"
                      ? "as a variant"
                      : detail.proposedClassification === "SUBSTITUTION"
                        ? "as a temporary swap"
                        : "no change"}
                </span>
                <span className="mp-chip muted">against v{recipe.currentVersion}</span>
              </div>
              {baseDiff && (
                <div style={{ marginTop: 10 }}>
                  <DiffView diff={baseDiff} />
                </div>
              )}
              {modify && baseDiff?.ingredientChanges[0]?.to && (
                <div className="rf-grid2" style={{ marginTop: 10, maxWidth: 360 }}>
                  <label>
                    <span className="field-label">
                      Modified quantity ({baseDiff.ingredientChanges[0].to.displayName})
                    </span>
                    <input
                      type="number"
                      className="text-input"
                      value={editedQty}
                      aria-label="Modified proposal quantity"
                      onChange={(e) => setEditedQty(e.target.value)}
                    />
                  </label>
                </div>
              )}
              <div className="advisor-panel-footer">
                <span className="advisor-panel-impact">
                  accept appends a version to the history (expand-then-accept —
                  the list row carries no optimisticVersion, spec §11 Q5)
                </span>
                <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                  {baseDiff?.ingredientChanges[0]?.to && (
                    <button
                      className="btn btn-small"
                      onClick={() => {
                        setModify((v) => !v);
                        setEditedQty(String(baseDiff.ingredientChanges[0]?.to?.quantity ?? ""));
                      }}
                    >
                      {modify ? "Accept as proposed instead" : "Modify before accepting"}
                    </button>
                  )}
                  <button className="btn btn-small" onClick={() => setRejecting(true)}>
                    Reject
                  </button>
                  <button
                    className="btn btn-small btn-primary"
                    onClick={() =>
                      acceptPendingChange(
                        detail.id,
                        buildUserEdits(),
                        detail.optimisticVersion,
                      )
                    }
                  >
                    {modify ? "Accept with edits" : "Accept"}
                  </button>
                </div>
              </div>
            </div>
          ) : null}
        </>
      )}
      {rejecting && listItem && (
        <Modal label="Reject suggestion" onClose={() => setRejecting(false)}>
          <div className="dialog-title">Reject this suggestion?</div>
          <div className="dialog-body">Optional note (≤200) — teaches the pipeline.</div>
          <input
            type="text"
            className="text-input"
            maxLength={200}
            value={reasonNote}
            aria-label="Rejection note"
            onChange={(e) => setReasonNote(e.target.value)}
          />
          <div className="modal-actions">
            <button className="btn" onClick={() => setRejecting(false)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                rejectPendingChange(listItem.id, reasonNote || undefined);
                setRejecting(false);
              }}
            >
              Reject
            </button>
          </div>
        </Modal>
      )}
      {history.length > 0 && (
        <div style={{ marginTop: listItem ? 12 : 8 }}>
          <button className="btn btn-small" onClick={() => setShowHistory((v) => !v)}>
            {showHistory ? "Hide earlier suggestions" : `Earlier suggestions (${history.length})`}
          </button>
          {showHistory &&
            history.map((h: PendingChangeDto) => (
              <div key={h.id} className="history-row" style={{ marginTop: 6 }}>
                <div style={{ minWidth: 0 }}>
                  <div className="history-query">
                    {DIMENSION_LABEL[h.changeDimension] ?? h.changeDimension}
                  </div>
                  <div className="history-meta">{shortWhen(h.createdAt)}</div>
                </div>
                <span className={`mp-chip${h.status === "ACCEPTED" || h.status === "MODIFIED" ? "" : " muted"}`}>
                  {h.status.toLowerCase()}
                </span>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}

/* ================= substitutions (§6) ========================================= */

const SUB_REASONS: SubstitutionReason[] = ["BUDGET", "AVAILABILITY", "DIETARY_TEMP", "EQUIPMENT"];

function SubstitutionRow({
  sub,
  recipe,
  versions,
}: {
  sub: RecipeSubstitutionDto;
  recipe: RecipeDto;
  versions: RecipeVersionDto[];
}) {
  const [promoting, setPromoting] = useState(false);
  const [changeReason, setChangeReason] = useState("");
  const promotedVersion = versions.find((v) => v.id === sub.promotedToVersionId);
  const stateChip =
    sub.state === "PROPOSED" ? (
      <span className="mp-chip amber">proposed</span>
    ) : sub.state === "ACCEPTED" ? (
      <TintChip>active overlay</TintChip>
    ) : sub.state === "REJECTED" ? (
      <span className="mp-chip muted">rejected</span>
    ) : (
      <span className="mp-chip muted">
        made permanent{promotedVersion ? ` → v${promotedVersion.versionNumber}` : ""}
      </span>
    );
  return (
    <div className="sub-row">
      <div className="swap-line" style={{ marginBottom: 4 }}>
        <span className="swap-from">
          {qtyStr(sub.original.quantity, sub.original.unit)} {sub.original.ingredientMappingKey}
        </span>
        <span className="swap-arrow">→</span>
        <span className="swap-to">
          {qtyStr(sub.substitute.quantity, sub.substitute.unit)} {sub.substitute.ingredientMappingKey}
        </span>
        {stateChip}
        {sub.temporary && <span className="mp-chip muted" title="until the constraint lifts">⏱ temporary</span>}
      </div>
      <div className="sub-meta">
        {sub.reason.toLowerCase().replace("_", " ")}
        {sub.constraintRef && <span className="sub-constraint"> · {sub.constraintRef}</span>}
        {sub.applicationCount > 0 && (
          <>
            {" "}· used in {sub.applicationCount} plan{sub.applicationCount === 1 ? "" : "s"}
            {sub.lastAppliedAt && ` (last ${shortWhen(sub.lastAppliedAt)})`}
          </>
        )}
        {" "}· {actorLabel(sub.createdByActor)} · {shortWhen(sub.createdAt)}
      </div>
      {(sub.methodOverlay ?? []).map((l) => (
        <div key={l.step} className="sub-overlay">
          step {l.step}: {l.instruction}
        </div>
      ))}
      {sub.notes && <div className="sub-notes">{sub.notes}</div>}
      {sub.state === "ACCEPTED" && sub.applicationCount >= 3 && (
        <div className="dedup-prewarn" style={{ marginTop: 6 }}>
          You've used this swap in {sub.applicationCount} plans — make it permanent?
        </div>
      )}
      <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
        {sub.state === "PROPOSED" && (
          <>
            <button
              className="btn btn-small"
              onClick={() =>
                actOnSubstitution(recipe.id, sub.id, "reject", { expectedVersion: sub.version })
              }
            >
              Reject
            </button>
            <button
              className="btn btn-small btn-primary"
              onClick={() =>
                actOnSubstitution(recipe.id, sub.id, "accept", { expectedVersion: sub.version })
              }
            >
              Accept
            </button>
          </>
        )}
        {sub.state === "ACCEPTED" && (
          <>
            <button
              className="btn btn-small"
              title="Reject = revert to original — overlay removed, original ingredient returns"
              onClick={() =>
                actOnSubstitution(recipe.id, sub.id, "reject", { expectedVersion: sub.version })
              }
            >
              Revert to original
            </button>
            <button className="btn btn-small btn-primary" onClick={() => setPromoting(true)}>
              Make permanent
            </button>
          </>
        )}
        {sub.state === "REJECTED" && (
          <button
            className="btn btn-small"
            title="Accept from REJECTED is legal in the shipped service — only SUPERSEDED is terminal (spec §11 Q3)"
            onClick={() =>
              actOnSubstitution(recipe.id, sub.id, "accept", { expectedVersion: sub.version })
            }
          >
            Re-accept
          </button>
        )}
      </div>
      {promoting && (
        <Modal label="Make swap permanent" onClose={() => setPromoting(false)}>
          <div className="dialog-title">Make this swap permanent?</div>
          <div className="dialog-body">
            Writes a new version (trigger SUBSTITUTION_PROMOTION); the overlay
            becomes the recipe and this substitution is superseded.
          </div>
          <span className="field-label">Change note * (1–2000)</span>
          <input
            type="text"
            className="text-input"
            value={changeReason}
            maxLength={2000}
            aria-label="Promotion change note"
            onChange={(e) => setChangeReason(e.target.value)}
          />
          <div className="modal-actions">
            <button className="btn" onClick={() => setPromoting(false)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              disabled={changeReason.trim().length === 0}
              onClick={() => {
                actOnSubstitution(recipe.id, sub.id, "promote-to-version", {
                  expectedVersion: sub.version,
                  changeReason: changeReason.trim(),
                });
                setPromoting(false);
              }}
            >
              Make permanent
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function ProposeSwapModal({
  recipe,
  version,
  onClose,
}: {
  recipe: RecipeDto;
  version: RecipeVersionDto;
  onClose: () => void;
}) {
  const [origKey, setOrigKey] = useState(version.ingredients[0]?.ingredientMappingKey ?? "");
  const [subKey, setSubKey] = useState("");
  const [subQty, setSubQty] = useState("");
  const [subUnit, setSubUnit] = useState("");
  const [reason, setReason] = useState<SubstitutionReason>("AVAILABILITY");
  const [constraintRef, setConstraintRef] = useState("");
  const [notes, setNotes] = useState("");
  const [temporary, setTemporary] = useState(true);
  const [overlayStep, setOverlayStep] = useState("");
  const [overlayText, setOverlayText] = useState("");

  const original = version.ingredients.find((i) => i.ingredientMappingKey === origKey);

  const submit = () => {
    if (!original || !subKey.trim() || subQty.trim() === "" || !subUnit.trim()) {
      pushToast("400 — substitute key, quantity and unit are required", "warn");
      return;
    }
    const ok = proposeSubstitution(recipe.id, {
      versionId: version.id,
      original: {
        ingredientMappingKey: original.ingredientMappingKey,
        quantity: original.quantity ?? 0,
        unit: original.unit ?? "x",
      },
      substitute: {
        ingredientMappingKey: subKey.trim().toLowerCase(),
        quantity: Number(subQty),
        unit: subUnit.trim().slice(0, 16),
      },
      reason,
      constraintRef: constraintRef.trim() === "" ? null : constraintRef.trim().slice(0, 160),
      methodOverlay:
        overlayStep.trim() !== "" && overlayText.trim() !== ""
          ? [{ step: Number(overlayStep), instruction: overlayText.trim().slice(0, 2000) }]
          : null,
      notes: notes.trim() === "" ? null : notes.trim().slice(0, 1000),
      temporary,
    });
    if (ok) onClose();
  };

  return (
    <Modal label="Propose a swap" onClose={onClose} wide>
      <div className="dialog-title">Propose a swap</div>
      <div className="dialog-body">
        Substitutions are overlays — the base recipe is unchanged. Lands as
        PROPOSED (note: PROPOSED rows have no list endpoint — client-remembered
        only, spec §11 Q2).
      </div>
      <div className="rf-meta-grid">
        <label>
          <span className="field-label">Original *</span>
          <select
            className="text-input time-select"
            value={origKey}
            aria-label="Original ingredient"
            onChange={(e) => setOrigKey(e.target.value)}
          >
            {version.ingredients.map((i) => (
              <option key={i.ingredientMappingKey} value={i.ingredientMappingKey}>
                {i.displayName}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="field-label">Substitute key *</span>
          <input type="text" className="text-input" value={subKey} maxLength={160}
            onChange={(e) => setSubKey(e.target.value)} aria-label="Substitute mapping key" />
        </label>
        <label>
          <span className="field-label">Qty *</span>
          <input type="number" className="text-input" value={subQty}
            onChange={(e) => setSubQty(e.target.value)} aria-label="Substitute quantity" />
        </label>
        <label>
          <span className="field-label">Unit *</span>
          <input type="text" className="text-input" value={subUnit} maxLength={16}
            onChange={(e) => setSubUnit(e.target.value)} aria-label="Substitute unit" />
        </label>
        <label>
          <span className="field-label">Reason *</span>
          <select className="text-input time-select" value={reason}
            onChange={(e) => setReason(e.target.value as SubstitutionReason)} aria-label="Substitution reason">
            {SUB_REASONS.map((r) => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
        </label>
        <label>
          <span className="field-label">Constraint ref</span>
          <input type="text" className="text-input" value={constraintRef} maxLength={160}
            onChange={(e) => setConstraintRef(e.target.value)} aria-label="Constraint reference" />
        </label>
      </div>
      <div className="rf-grid2" style={{ marginTop: 8 }}>
        <label>
          <span className="field-label">Method overlay — step ≥1</span>
          <input type="number" className="text-input" min={1} value={overlayStep}
            onChange={(e) => setOverlayStep(e.target.value)} aria-label="Overlay step number" />
        </label>
        <label>
          <span className="field-label">Overlay instruction</span>
          <input type="text" className="text-input" value={overlayText} maxLength={2000}
            onChange={(e) => setOverlayText(e.target.value)} aria-label="Overlay instruction" />
        </label>
      </div>
      <label style={{ display: "block", marginTop: 8 }}>
        <span className="field-label">Notes (≤1000)</span>
        <input type="text" className="text-input" value={notes} maxLength={1000}
          onChange={(e) => setNotes(e.target.value)} aria-label="Substitution notes" />
      </label>
      <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 10, fontSize: 13 }}>
        <input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} />
        Temporary — until the constraint lifts (default)
      </label>
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={submit}>Propose</button>
      </div>
    </Modal>
  );
}

/* ================= ratings (§7) =============================================== */

const AXES = [
  { key: "taste", label: "Taste" },
  { key: "effortWorthIt", label: "Worth the effort" },
  { key: "portionFit", label: "Portion fit" },
  { key: "repeatValue", label: "Would repeat" },
] as const;

function RateModal({
  recipe,
  version,
  onClose,
}: {
  recipe: RecipeDto;
  version: RecipeVersionDto;
  onClose: () => void;
}) {
  const [params] = useSearchParams();
  const slotId = params.get("slotId"); // Today deep link pre-fill — no manual control
  const mine = useStore((s) =>
    (s.recipeData.ratings[recipe.id] ?? []).find(
      (r) => r.versionId === version.id && r.userId.startsWith("user-"),
    ),
  );
  const [taste, setTaste] = useState(mine?.taste ?? 75);
  const [detailOpen, setDetailOpen] = useState(
    mine != null && (mine.effortWorthIt != null || mine.portionFit != null || mine.repeatValue != null),
  );
  const [effort, setEffort] = useState(mine?.effortWorthIt ?? 75);
  const [portion, setPortion] = useState(mine?.portionFit ?? 75);
  const [repeat, setRepeat] = useState(mine?.repeatValue ?? 75);
  const [notes, setNotes] = useState(mine?.notes ?? "");

  const submit = () => {
    const req = {
      versionId: version.id,
      slotId: slotId ?? null,
      taste,
      effortWorthIt: detailOpen ? effort : null,
      portionFit: detailOpen ? portion : null,
      repeatValue: detailOpen ? repeat : null,
      notes: notes.trim() === "" ? null : notes.trim().slice(0, 1000),
    };
    if (mine) updateRating(recipe.id, mine.id, req, mine.optimisticVersion);
    else submitRating(recipe.id, req);
    onClose();
  };

  const slider = (label: string, value: number, set: (v: number) => void) => (
    <label style={{ display: "block", marginTop: 10 }}>
      <span className="field-label">
        {label} — <span className="mp-num" style={{ fontSize: 13 }}>{value}</span>/100
      </span>
      <input
        type="range"
        min={0}
        max={100}
        value={value}
        style={{ width: "100%" }}
        aria-label={`${label} 0 to 100`}
        onChange={(e) => set(Number(e.target.value))}
      />
    </label>
  );

  return (
    <Modal label="Rate this version" onClose={onClose}>
      <div className="dialog-title">
        {mine ? "Update your rating" : "Rate this version"} — v{version.versionNumber}
      </div>
      <div className="dialog-body">
        Taste is the one-tap path; the other three axes are optional and
        coalesce to taste in the aggregate.
        {slotId && <> Rating tonight's slot ({slotId}).</>}
      </div>
      {slider("Taste *", taste, setTaste)}
      {!detailOpen ? (
        <button className="btn btn-small" style={{ marginTop: 10 }} onClick={() => setDetailOpen(true)}>
          Rate in detail
        </button>
      ) : (
        <>
          {slider("Worth the effort", effort, setEffort)}
          {slider("Portion fit", portion, setPortion)}
          {slider("Would repeat", repeat, setRepeat)}
        </>
      )}
      <label style={{ display: "block", marginTop: 12 }}>
        <span className="field-label">Notes (≤1000)</span>
        <input type="text" className="text-input" value={notes} maxLength={1000}
          onChange={(e) => setNotes(e.target.value)} aria-label="Rating notes" />
      </label>
      <div className="modal-actions">
        {mine && (
          <button
            className="btn btn-danger"
            onClick={() => {
              deleteRating(recipe.id, mine.id);
              onClose();
            }}
          >
            Delete rating
          </button>
        )}
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={submit}>
          {mine ? "Update rating" : "Submit rating"}
        </button>
      </div>
    </Modal>
  );
}

/* ================= version history (§5b) ====================================== */

function VersionHistory({
  recipe,
  branchVersions,
  viewedVersion,
  onView,
}: {
  recipe: RecipeDto;
  branchVersions: RecipeVersionDto[];
  viewedVersion: RecipeVersionDto;
  onView: (n: number) => void;
}) {
  const [openDiffFor, setOpenDiffFor] = useState<string | null>(null);
  const [revertFor, setRevertFor] = useState<RecipeVersionDto | null>(null);
  const head = branchVersions[branchVersions.length - 1];
  const newestFirst = [...branchVersions].reverse();

  return (
    <div className="mp-card detail-card" style={{ marginTop: 18 }}>
      <div className="detail-card-head">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Version history · {branchVersions.length} on this branch
        </span>
      </div>
      {newestFirst.map((v) => {
        const parent = branchVersions.find((x) => x.id === v.parentVersionId);
        const sameBranchParent = parent != null && parent.branchId === v.branchId;
        const isHead = v.id === head?.id;
        return (
          <div key={v.id} className="version-row">
            <div className="version-row-head">
              <button
                className={`version-pill${v.id === viewedVersion.id ? " current" : ""}`}
                onClick={() => onView(v.versionNumber)}
                title="Open this version read-only"
              >
                v{v.versionNumber}
                {isHead ? " · current" : ""}
              </button>
              <span className="mp-chip muted">{TRIGGER_LABEL[v.trigger]}</span>
              <span className="version-meta">
                {actorLabel(v.createdByActor)} · {shortWhen(v.createdAt)}
              </span>
              {!sameBranchParent && v.parentVersionId && (
                <span className="version-meta">from another branch</span>
              )}
              <span style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
                {sameBranchParent && (
                  <button
                    className="btn btn-small"
                    onClick={() => setOpenDiffFor(openDiffFor === v.id ? null : v.id)}
                  >
                    What changed
                  </button>
                )}
                {!isHead && (
                  <button className="btn btn-small" onClick={() => setRevertFor(v)}>
                    Revert
                  </button>
                )}
              </span>
            </div>
            {v.changeReason && <div className="version-note">{v.changeReason}</div>}
            {openDiffFor === v.id && parent && (
              <div style={{ marginTop: 8 }}>
                <DiffView diff={computeDiff(parent, v)} />
              </div>
            )}
          </div>
        );
      })}
      {revertFor && (
        <Modal label="Revert version" onClose={() => setRevertFor(null)}>
          <div className="dialog-title">Revert to v{revertFor.versionNumber}?</div>
          <div className="dialog-body">
            Writes a new version copying v{revertFor.versionNumber} — nothing is
            deleted; history is never rewritten.
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={() => setRevertFor(null)}>Cancel</button>
            <button
              className="btn btn-primary"
              onClick={() => {
                revertRecipeAction(recipe, revertFor);
                setRevertFor(null);
              }}
            >
              Revert
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function revertRecipeAction(recipe: RecipeDto, target: RecipeVersionDto): void {
  // RevertToVersionRequest: branchId* + versionNumber* + optimistic guard.
  revertRecipe(recipe.id, {
    branchId: target.branchId,
    versionNumber: target.versionNumber,
    expectedRecipeOptimisticVersion: recipe.optimisticVersion,
  });
}

/* ================= fork modal (§5a) =========================================== */

function ForkModal({
  recipe,
  branchVersions,
  onClose,
}: {
  recipe: RecipeDto;
  branchVersions: RecipeVersionDto[];
  onClose: () => void;
}) {
  const [name, setName] = useState("");
  const [label, setLabel] = useState("");
  const [reason, setReason] = useState("");
  const [forkPointId, setForkPointId] = useState(
    branchVersions[branchVersions.length - 1]?.id ?? "",
  );
  const forkPoint = branchVersions.find((v) => v.id === forkPointId);

  return (
    <Modal label="Fork as variant" onClose={onClose} wide>
      <div className="dialog-title">Fork as variant</div>
      <div className="dialog-body">
        Branches are forks with independent merit (protein swaps, flavour
        directions). Seasoning tweaks should be a version, not a branch.
      </div>
      <div className="rf-meta-grid">
        <label>
          <span className="field-label">Slug name * (a–z, 0–9, -)</span>
          <input type="text" className="text-input" value={name} maxLength={64}
            onChange={(e) => setName(e.target.value)} aria-label="Branch slug name" />
        </label>
        <label>
          <span className="field-label">Display label</span>
          <input type="text" className="text-input" value={label} maxLength={120}
            onChange={(e) => setLabel(e.target.value)} aria-label="Branch label" />
        </label>
        <label>
          <span className="field-label">Fork point *</span>
          <select className="text-input time-select" value={forkPointId}
            onChange={(e) => setForkPointId(e.target.value)} aria-label="Fork point version">
            {branchVersions.map((v) => (
              <option key={v.id} value={v.id}>v{v.versionNumber}</option>
            ))}
          </select>
        </label>
      </div>
      <label style={{ display: "block", marginTop: 8 }}>
        <span className="field-label">Why * (1–2000)</span>
        <input type="text" className="text-input" value={reason} maxLength={2000}
          onChange={(e) => setReason(e.target.value)} aria-label="Branch reason" />
      </label>
      {forkPoint && (
        <>
          <div className="inline-note" style={{ margin: "12px 0 4px" }}>
            Body editor — pre-filled from v{forkPoint.versionNumber}; the
            character fingerprint is derived server-side (no control — spec §11 Q7).
          </div>
          <RecipeForm
            initial={requestFromVersion(recipe, forkPoint)}
            submitLabel="Create branch"
            extraValid={name.trim() !== "" && reason.trim() !== ""}
            extra={
              (name.trim() === "" || reason.trim() === "") && (
                <div className="inline-note">Slug name and reason are required.</div>
              )
            }
            onSubmit={(req) => {
              const branchId = createVariantBranch(recipe.id, {
                name: name.trim(),
                label: label.trim() === "" ? null : label.trim(),
                reason: reason.trim(),
                branchPointVersionId: forkPoint.id,
                body: {
                  ingredients: req.ingredients,
                  method: req.method,
                  metadata: req.metadata,
                  tags: req.tags ?? null,
                },
                fingerprintOverride: null,
              });
              if (branchId) onClose();
            }}
            onCancel={onClose}
          />
        </>
      )}
    </Modal>
  );
}

/* ================= page ======================================================= */

export function RecipeDetail() {
  const { id } = useParams<{ id: string }>();
  const recipe = useStore((s) => s.recipes.find((r) => r.id === id));
  // Live mode leaves per-recipe detail (versions/subs/ratings) unhydrated at
  // boot; fetch it for this :id on mount and whenever the route id changes.
  useEffect(() => {
    if (LIVE && id) void hydrateRecipeDetail(id);
  }, [id]);
  if (!recipe) {
    return (
      <div>
        <Link to="/recipes" className="back-link">← Recipes</Link>
        <div className="page-loading">Recipe not found.</div>
      </div>
    );
  }
  return <Detail recipe={recipe} />;
}

function Detail({ recipe }: { recipe: RecipeDto }) {
  const store = useStore((s) => s);
  const subs = store.recipeData.substitutions[recipe.id] ?? [];
  const ratings = store.recipeData.ratings[recipe.id] ?? [];
  const provenance = store.recipeData.provenance[recipe.id];

  const currentBranchId = recipe.currentBranchId ?? `${recipe.id}-main`;
  const [viewedBranchId, setViewedBranchId] = useState(currentBranchId);
  const branchVersions = versionsFor(store as StoreState, recipe.id, viewedBranchId);
  const head = branchVersions[branchVersions.length - 1];
  const [viewedN, setViewedN] = useState<number | null>(null);
  const baseVersion =
    (viewedN != null && branchVersions.find((v) => v.versionNumber === viewedN)) || head;
  const [withSubs, setWithSubs] = useState(false);
  const viewed = baseVersion
    ? withSubs
      ? versionWithSubstitutions(baseVersion, subs)
      : baseVersion
    : undefined;

  const [rating, setRating] = useState(false);
  const [editing, setEditing] = useState(false);
  const [forking, setForking] = useState(false);
  const [proposing, setProposing] = useState(false);
  const [showAllRatings, setShowAllRatings] = useState(false);

  if (!viewed || !baseVersion) {
    return (
      <div>
        <Link to="/recipes" className="back-link">← Recipes</Link>
        <div className="page-loading">No versions on this branch.</div>
      </div>
    );
  }

  const isSystem = recipe.catalogue === "SYSTEM";
  const archived = recipe.archivedAt != null;
  const meta = viewed.metadata;
  const recipeSummary = computeRatingSummary(ratings); // recipe-level (hero)
  const versionSummary = computeRatingSummary(ratings, baseVersion.id);
  const myRating = ratings.find((r) => r.versionId === baseVersion.id);
  const nutritionResult = store.recipeData.nutritionByVersion[baseVersion.id];
  const branch = recipe.branches.find((b) => b.id === viewedBranchId);
  const acceptedOnViewed = subs.filter(
    (s) => s.state === "ACCEPTED" && s.versionId === baseVersion.id,
  );
  const acceptedAnywhere = subs.filter((s) => s.state === "ACCEPTED").length;
  const swapByKey = new Map(
    acceptedOnViewed.map((s) => [s.original.ingredientMappingKey, s]),
  );
  const viewingOld = head != null && baseVersion.id !== head.id;

  return (
    <div>
      <div className="detail-topbar">
        <Link to="/recipes" className="back-link">← Recipes</Link>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
          <button
            className="btn"
            onClick={() => recalculateNutrition(recipe.id)}
            title="POST /nutrition/recipes/{id}/versions/{vid}/recalculate"
          >
            Recalculate nutrition
          </button>
          {!isSystem && (
            <>
              <button className="btn" onClick={() => setRating(true)}>
                {myRating ? "Update your rating" : "Rate"}
              </button>
              <button className="btn btn-primary" onClick={() => setEditing(true)} disabled={archived}>
                Edit
              </button>
            </>
          )}
        </div>
      </div>

      {isSystem && (
        <div className="catalogue-banner">
          <span>Pool recipe — add it to your library to edit, rate or substitute.</span>
          <button className="btn btn-small btn-primary" onClick={() => promoteRecipe(recipe.id)}>
            Add to my library
          </button>
        </div>
      )}
      {archived && (
        <div className="catalogue-banner archived">
          <span>Archived — excluded from the planner index; data retained.</span>
          <button className="btn btn-small" onClick={() => unarchiveRecipe(recipe.id)}>
            Unarchive
          </button>
        </div>
      )}

      <div className="detail-hero">
        <div>
          <div className="detail-photo">
            {recipe.imageUrl && (
              <img
                src={recipe.imageUrl}
                alt={recipe.name}
                onError={(e) => {
                  e.currentTarget.style.display = "none";
                }}
              />
            )}
          </div>
          <div style={{ marginTop: 8 }}>
            <ImageControl recipe={recipe} />
          </div>
        </div>
        <div>
          <h1 className="page-title" style={{ fontSize: 30 }}>{recipe.name}</h1>
          {recipe.description && <div className="page-meta">{recipe.description}</div>}
          <div className="detail-chips">
            {meta && (
              <>
                <span
                  className="detail-chip"
                  title={`prep ${meta.prepTimeMins} min + cook ${meta.cookTimeMins} min`}
                >
                  {meta.totalTimeMins} min
                </span>
                <span className="detail-chip">Serves {meta.servings}</span>
                {meta.cuisine && <span className="detail-chip">{meta.cuisine}</span>}
                {meta.packable && <span className="detail-chip">packable</span>}
                {(meta.fridgeDays != null || meta.freezerWeeks != null) && (
                  <span className="detail-chip">
                    keeps{meta.fridgeDays != null ? ` ${meta.fridgeDays} d fridge` : ""}
                    {meta.fridgeDays != null && meta.freezerWeeks != null ? " /" : ""}
                    {meta.freezerWeeks != null ? ` ${meta.freezerWeeks} w freezer` : ""}
                  </span>
                )}
                {meta.equipmentRequired.length > 0 && (
                  <span className="detail-chip">{meta.equipmentRequired.join(" · ")}</span>
                )}
                {meta.mealTypes.length > 0 && (
                  <span className="detail-chip">{meta.mealTypes.join(" · ").toLowerCase()}</span>
                )}
              </>
            )}
            <QualityBadge quality={recipe.dataQuality} />
            <span className="version-tag">
              v{recipe.currentVersion}
              {branch && branch.name !== "main" ? ` · ${branch.label ?? branch.name}` : ""}
            </span>
          </div>
          {viewed.tags && (
            <div className="detail-chips" style={{ marginTop: 6 }}>
              {viewed.tags.protein && <span className="tier-badge">{viewed.tags.protein}</span>}
              {viewed.tags.cookingMethod && <span className="tier-badge">{viewed.tags.cookingMethod}</span>}
              {viewed.tags.complexity && <span className="tier-badge">{viewed.tags.complexity.toLowerCase()}</span>}
              {viewed.tags.flavourProfile.map((f) => (
                <span key={f} className="tier-badge">{f}</span>
              ))}
              {viewed.tags.dietaryFlags.map((f) => (
                <span key={f} className="tier-badge">{f}</span>
              ))}
            </div>
          )}
          <div className="detail-ratings">
            <div>
              <span className="mp-label">Rating</span>
              <div style={{ margin: "7px 0 8px" }}>
                <span className="mp-num" style={{ fontSize: 26 }}>
                  {recipeSummary.avgAggregate == null
                    ? "—"
                    : Math.round(recipeSummary.avgAggregate)}
                </span>
                <span className="stat-target" style={{ marginLeft: 6 }}>
                  · {recipeSummary.count} rating{recipeSummary.count === 1 ? "" : "s"} · all versions
                </span>
              </div>
            </div>
            <div style={{ alignSelf: "end" }}>
              <NutritionStatusNote
                status={recipe.nutritionStatus}
                needsReview={needsReviewCountOf(recipe)}
              />
            </div>
          </div>
          <NutritionBand
            result={nutritionResult}
            recipeId={recipe.id}
            servings={meta?.servings}
          />
        </div>
      </div>

      <AdaptationCard recipe={recipe} />

      {/* branch strip */}
      <div className="versions-strip" style={{ flexWrap: "wrap" }}>
        <span className="mp-label">Branches</span>
        {[...recipe.branches]
          .sort((a, b) => (a.name === "main" ? -1 : b.name === "main" ? 1 : a.createdAt.localeCompare(b.createdAt)))
          .map((b) => (
            <button
              key={b.id}
              className={`filter-chip${b.id === viewedBranchId ? " active" : ""}`}
              title={[
                b.reason ?? "",
                `${actorLabel(b.createdByActor)} · ${shortWhen(b.createdAt)}`,
                b.branchPointVersionId ? "forked from main" : "",
                `v${b.currentVersion}`,
              ]
                .filter(Boolean)
                .join(" · ")}
              onClick={() => {
                setViewedBranchId(b.id);
                setViewedN(null);
                setWithSubs(false);
              }}
            >
              {b.label ?? b.name}
              {b.id === currentBranchId ? " ●" : ""}
            </button>
          ))}
        {!isSystem && (
          <button className="btn btn-small" onClick={() => setForking(true)}>
            Fork as variant
          </button>
        )}
        {branch && branch.divergenceScore > 0.7 && (
          <span className="mp-serif" style={{ fontSize: 16 }}>
            this branch has become its own dish
            <span
              className="version-meta"
              style={{ marginLeft: 6 }}
              title="promote-to-standalone has no endpoint yet (spec §11 Q4) — informational only"
            >
              (promote: no endpoint)
            </span>
          </span>
        )}
      </div>

      {viewingOld && (
        <div className="catalogue-banner" style={{ marginTop: 10 }}>
          <span>
            Viewing v{baseVersion.versionNumber} of {head?.versionNumber} (read-only).
          </span>
          <button className="btn btn-small" onClick={() => setViewedN(null)}>
            Back to current
          </button>
        </div>
      )}

      <div className="detail-columns">
        <div className="mp-card detail-card">
          <div className="detail-card-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>Ingredients</span>
            {(acceptedOnViewed.length > 0 || withSubs) && (
              <button className="btn btn-small" onClick={() => setWithSubs((v) => !v)}>
                {withSubs
                  ? `${viewed.appliedSubstitutionIds?.length ?? 0} swap${(viewed.appliedSubstitutionIds?.length ?? 0) === 1 ? "" : "s"} applied — view original`
                  : "View with substitutions"}
              </button>
            )}
          </div>
          {viewed.ingredients
            .slice()
            .sort((a, b) => a.lineOrder - b.lineOrder)
            .map((it) => {
              const swap = !withSubs ? swapByKey.get(it.ingredientMappingKey) : undefined;
              return (
                <div key={`${it.ingredientMappingKey}-${it.lineOrder}`} className="ingredient-row">
                  <span style={{ fontSize: 14, minWidth: 0 }}>
                    {it.displayName}
                    {it.preparation && <span className="ingredient-prep">, {it.preparation}</span>}
                    {it.optional && <span className="ingredient-prep"> (optional)</span>}
                    {it.needsReview && (
                      <span
                        className="needs-review-dot"
                        title={`USDA match ${(it.mappingConfidence ?? 0).toFixed(2)} — nutrition may be off`}
                      >
                        ●
                      </span>
                    )}
                    {swap && (
                      <span style={{ marginLeft: 9 }}>
                        <TintChip>swap: {titleCase(swap.substitute.ingredientMappingKey)}</TintChip>
                      </span>
                    )}
                  </span>
                  <span className="ingredient-qty">{qtyStr(it.quantity, it.unit)}</span>
                </div>
              );
            })}
        </div>
        <div className="mp-card detail-card">
          <div className="detail-card-head" style={{ marginBottom: 12 }}>
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>Method</span>
          </div>
          <div style={{ display: "grid", gap: 12 }}>
            {viewed.methodSteps
              .slice()
              .sort((a, b) => a.stepNumber - b.stepNumber)
              .map((step) => (
                <div key={step.stepNumber} className="method-step">
                  <span className="mp-num" style={{ fontSize: 14, color: "var(--mp-terra)" }}>
                    {String(step.stepNumber).padStart(2, "0")}
                  </span>
                  <span style={{ minWidth: 0 }}>
                    {step.instruction}
                    {step.durationMinutes != null && (
                      <span className="ingredient-prep"> · ~{step.durationMinutes} min</span>
                    )}
                  </span>
                </div>
              ))}
          </div>
        </div>
      </div>

      {/* substitutions panel */}
      <div className="mp-card detail-card" style={{ marginTop: 18 }}>
        <div className="detail-card-head">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Substitutions · {acceptedAnywhere} active
          </span>
          {!isSystem && (
            <button className="btn btn-small" onClick={() => setProposing(true)}>
              Propose a swap
            </button>
          )}
        </div>
        {subs.length === 0 ? (
          <div className="page-loading" style={{ padding: "14px 0" }}>
            No substitutions — overlays appear here when constraints bite.
          </div>
        ) : (
          subs.map((sub) => (
            <SubstitutionRow
              key={sub.id}
              sub={sub}
              recipe={recipe}
              versions={Object.values(store.recipeData.versions[recipe.id] ?? {}).flat()}
            />
          ))
        )}
      </div>

      {/* ratings band (per viewed version) */}
      <div className="mp-card detail-card" style={{ marginTop: 18 }}>
        <div className="detail-card-head">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Ratings — v{baseVersion.versionNumber}
          </span>
          <span className="version-meta">
            {versionSummary.count === 0
              ? "not rated yet"
              : `${Math.round(versionSummary.avgAggregate ?? 0)} · ${versionSummary.count} rating${versionSummary.count === 1 ? "" : "s"}`}
          </span>
        </div>
        <div className="detail-ratings" style={{ marginTop: 8 }}>
          {AXES.map((axis) => {
            const val =
              axis.key === "taste"
                ? versionSummary.avgTaste
                : axis.key === "effortWorthIt"
                  ? versionSummary.avgEffortWorthIt
                  : axis.key === "portionFit"
                    ? versionSummary.avgPortionFit
                    : versionSummary.avgRepeatValue;
            return (
              <div key={axis.key}>
                <span className="mp-label">{axis.label}</span>
                <div style={{ margin: "7px 0 8px" }}>
                  <span className="mp-num" style={{ fontSize: 26 }}>
                    {val == null ? "—" : Math.round(val)}
                  </span>
                </div>
                <SegmentBar pct={(val ?? 0) / 100} width={110} />
              </div>
            );
          })}
        </div>
        <div style={{ display: "flex", gap: 10, marginTop: 12, flexWrap: "wrap" }}>
          {!isSystem && (
            <button className="btn btn-small" onClick={() => setRating(true)}>
              {myRating ? "Update your rating" : "Rate this version"}
            </button>
          )}
          {ratings.length > 0 && (
            <button className="btn btn-small" onClick={() => setShowAllRatings((v) => !v)}>
              {showAllRatings ? "Hide all ratings" : `All ratings (${ratings.length})`}
            </button>
          )}
        </div>
        {showAllRatings &&
          ratings.map((r) => (
            <div key={r.id} className="version-row" style={{ marginTop: 8 }}>
              <div className="version-row-head">
                <span className="mp-num" style={{ fontSize: 18 }}>{Math.round(r.aggregate)}</span>
                <span className="version-meta">
                  T {r.taste}
                  {r.effortWorthIt != null && ` · E ${r.effortWorthIt}`}
                  {r.portionFit != null && ` · P ${r.portionFit}`}
                  {r.repeatValue != null && ` · R ${r.repeatValue}`}
                </span>
                <span className="version-meta">
                  v{Object.values(store.recipeData.versions[recipe.id] ?? {})
                    .flat()
                    .find((v) => v.id === r.versionId)?.versionNumber ?? "?"}
                  {" · "}
                  {shortWhen(r.createdAt)}
                  {r.updatedAt !== r.createdAt && " · edited"}
                  {" · you"}
                </span>
              </div>
              {r.notes && <div className="version-note">{r.notes}</div>}
            </div>
          ))}
      </div>

      <VersionHistory
        recipe={recipe}
        branchVersions={branchVersions}
        viewedVersion={baseVersion}
        onView={(n) => {
          setViewedN(n);
          setWithSubs(false);
        }}
      />

      {provenance && (
        <details className="micros-details" style={{ marginTop: 18 }}>
          <summary>Where this came from</summary>
          <div style={{ display: "grid", gap: 6, marginTop: 10, fontSize: 13.5 }}>
            <div>
              <span className="tier-badge">{provenance.sourceType.toLowerCase().replace("_", " ")}</span>
              {recipe.dataQuality !== "AI_GENERATED" && provenance.extractionMethod && (
                <span className="version-meta" style={{ marginLeft: 8 }}>
                  {provenance.extractionMethod === "json_ld"
                    ? "read from the page's recipe data"
                    : provenance.extractionMethod}
                </span>
              )}
            </div>
            {/* G10: the graph@…+c@… stamp IS the audit trail — verbatim, never paraphrased. */}
            {recipe.dataQuality === "AI_GENERATED" && provenance.extractionMethod && (
              <div
                className="version-meta"
                style={{ fontFamily: "monospace", fontSize: 12.5 }}
                title="Generator audit stamp: graph commit + corpus fingerprint"
              >
                {provenance.extractionMethod}
              </div>
            )}
            {recipe.dataQuality === "AI_GENERATED" && provenance.sourceKey && (
              <div className="version-meta" style={{ fontFamily: "monospace", fontSize: 12.5 }}>
                {provenance.sourceKey}
              </div>
            )}
            {provenance.sourceUrl && (
              <a href={provenance.sourceUrl} target="_blank" rel="noopener noreferrer">
                {provenance.sourceUrl}
              </a>
            )}
            <div className="version-meta">
              imported {shortWhen(provenance.importedAt)} · by you
            </div>
            {provenance.duplicateOfRecipeId && (
              <div className="version-meta">
                imported as a duplicate of{" "}
                <Link to={`/recipes/${provenance.duplicateOfRecipeId}`}>this recipe</Link>
              </div>
            )}
          </div>
        </details>
      )}

      <div style={{ display: "flex", gap: 10, marginTop: 22 }}>
        {!isSystem && !archived && (
          <>
            <button className="btn btn-small" onClick={() => archiveRecipe(recipe.id)}>
              Archive
            </button>
            <button
              className="btn btn-small"
              title="Stays in the recipe pool — your versions are preserved"
              onClick={() => demoteRecipe(recipe.id)}
            >
              Remove from my library
            </button>
          </>
        )}
      </div>

      {rating && <RateModal recipe={recipe} version={baseVersion} onClose={() => setRating(false)} />}
      {proposing && (
        <ProposeSwapModal recipe={recipe} version={baseVersion} onClose={() => setProposing(false)} />
      )}
      {forking && (
        <ForkModal recipe={recipe} branchVersions={branchVersions} onClose={() => setForking(false)} />
      )}
      {editing && head && (
        <EditModal recipe={recipe} head={head} onClose={() => setEditing(false)} />
      )}
    </div>
  );
}

function EditModal({
  recipe,
  head,
  onClose,
}: {
  recipe: RecipeDto;
  head: RecipeVersionDto;
  onClose: () => void;
}) {
  const [changeReason, setChangeReason] = useState("");
  return (
    <Modal label="Edit recipe" onClose={onClose} wide>
      <div className="dialog-title">Edit — creates v{head.versionNumber + 1}</div>
      <div className="dialog-body">
        Full replacement (PUT); the change lands as a new version on{" "}
        {recipe.branches.find((b) => b.id === head.branchId)?.label ?? "main"}.
      </div>
      <RecipeForm
        initial={requestFromVersion(recipe, head)}
        submitLabel="Save (new version)"
        extraValid={changeReason.trim().length > 0}
        extra={
          <label style={{ display: "block" }}>
            <span className="field-label">Change note * — what did you change and why (1–2000)</span>
            <input
              type="text"
              className="text-input"
              value={changeReason}
              maxLength={2000}
              aria-label="Change reason"
              onChange={(e) => setChangeReason(e.target.value)}
            />
            {changeReason.trim().length === 0 && (
              <span className="inline-note">Required — it lands on the version row.</span>
            )}
          </label>
        }
        onSubmit={(req) => {
          const outcome = editRecipe(recipe.id, {
            ...req,
            changeReason: changeReason.trim(),
            expectedOptimisticVersion: recipe.optimisticVersion,
          });
          if (outcome === "ok" || outcome === "conflict") onClose();
        }}
        onCancel={onClose}
      />
    </Modal>
  );
}

/** Ingredient line used by the read-only old-version banner tooltip. */
export const ingredientLine = fmtIngredient;
