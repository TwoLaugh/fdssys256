/**
 * Pure recipe-domain helpers shared by the mock store, the seed and the
 * pages: request⇄DTO conversion, the structured version diff (the mock
 * equivalent of GET …/versions/{from}/diff/{to}), the with-substitutions
 * overlay projection, and rating aggregation. No store imports — everything
 * here is referentially transparent.
 */

import type {
  CreateIngredientRequest,
  CreateMethodStepRequest,
  CreateRecipeRequest,
  IngredientChangeDto,
  IngredientDto,
  MethodStepDto,
  RecipeDiffDto,
  RecipeDto,
  RecipeRatingDto,
  RecipeRatingSummaryDto,
  RecipeSubstitutionDto,
  RecipeVersionDto,
} from "./types";

/* ---- ids -------------------------------------------------------------------- */

export function mainBranchId(recipeId: string): string {
  return `${recipeId}-main`;
}

/* ---- request ⇄ DTO conversion -------------------------------------------------- */

let ingredientSeq = 1000;

/** CreateIngredientRequest → IngredientDto; preserves needs-review flags from
 *  a prior version by mapping key (an edit doesn't re-run USDA mapping). */
export function ingredientsFromRequest(
  reqs: CreateIngredientRequest[],
  prior?: IngredientDto[],
): IngredientDto[] {
  return reqs.map((r) => {
    const before = prior?.find(
      (p) => p.ingredientMappingKey === r.ingredientMappingKey,
    );
    return {
      id: `ing-${++ingredientSeq}`,
      lineOrder: r.lineOrder,
      ingredientMappingKey: r.ingredientMappingKey,
      displayName: r.displayName,
      quantity: r.quantity ?? null,
      unit: r.unit ?? null,
      preparation: r.preparation ?? null,
      optional: r.optional ?? false,
      needsReview: before?.needsReview ?? false,
      mappingConfidence: before?.mappingConfidence ?? 0.93,
    };
  });
}

export function stepsFromRequest(
  reqs: CreateMethodStepRequest[],
): MethodStepDto[] {
  return reqs.map((r) => ({
    id: `ms-${++ingredientSeq}`,
    stepNumber: r.stepNumber,
    instruction: r.instruction,
    durationMinutes: r.durationMinutes ?? null,
  }));
}

function ingredientToRequest(i: IngredientDto): CreateIngredientRequest {
  return {
    lineOrder: i.lineOrder,
    ingredientMappingKey: i.ingredientMappingKey,
    displayName: i.displayName,
    quantity: i.quantity ?? null,
    unit: i.unit ?? null,
    preparation: i.preparation ?? null,
    optional: i.optional,
  };
}

/** RecipeDto + viewed version → the §4d form shape (edit pre-fill). */
export function requestFromVersion(
  dto: RecipeDto,
  version: RecipeVersionDto,
): CreateRecipeRequest {
  return {
    name: dto.name,
    description: dto.description ?? null,
    ingredients: version.ingredients.map(ingredientToRequest),
    method: version.methodSteps.map((s) => ({
      stepNumber: s.stepNumber,
      instruction: s.instruction,
      durationMinutes: s.durationMinutes ?? null,
    })),
    metadata: {
      servings: version.metadata?.servings ?? 2,
      prepTimeMins: version.metadata?.prepTimeMins ?? 10,
      cookTimeMins: version.metadata?.cookTimeMins ?? 20,
      totalTimeMins: version.metadata?.totalTimeMins ?? 30,
      equipmentRequired: version.metadata?.equipmentRequired ?? [],
      fridgeDays: version.metadata?.fridgeDays ?? null,
      freezerWeeks: version.metadata?.freezerWeeks ?? null,
      packable: version.metadata?.packable ?? false,
      cuisine: version.metadata?.cuisine ?? null,
      mealTypes: version.metadata?.mealTypes ?? [],
    },
    tags: version.tags
      ? {
          protein: version.tags.protein ?? null,
          cookingMethod: version.tags.cookingMethod ?? null,
          complexity: version.tags.complexity ?? null,
          flavourProfile: version.tags.flavourProfile,
          dietaryFlags: version.tags.dietaryFlags,
        }
      : null,
  };
}

/* ---- structured diff (#9 — RecipeDiffDto) -------------------------------------- */

type IngredientSnapshot = NonNullable<IngredientChangeDto["from"]>;

function snap(i: IngredientDto): IngredientSnapshot {
  return {
    ingredientMappingKey: i.ingredientMappingKey,
    displayName: i.displayName,
    quantity: i.quantity ?? null,
    unit: i.unit ?? null,
    preparation: i.preparation ?? null,
    optional: i.optional,
    lineOrder: i.lineOrder,
  };
}

/** First differing scalar field on a MODIFIED ingredient row. */
function fieldChanged(a: IngredientDto, b: IngredientDto): string | null {
  if ((a.quantity ?? null) !== (b.quantity ?? null)) return "quantity";
  if ((a.unit ?? null) !== (b.unit ?? null)) return "unit";
  if ((a.preparation ?? null) !== (b.preparation ?? null)) return "preparation";
  if (a.displayName !== b.displayName) return "displayName";
  if (a.optional !== b.optional) return "optional";
  return null;
}

/**
 * Mock equivalent of the stored-key diff lookup between two CONSECUTIVE
 * same-branch versions; the UI guards consecutiveness client-side (§5b).
 */
export function computeDiff(
  from: RecipeVersionDto,
  to: RecipeVersionDto,
): RecipeDiffDto {
  const ingredientChanges: RecipeDiffDto["ingredientChanges"] = [];
  const byKey = (vs: IngredientDto[]) =>
    new Map(vs.map((i) => [i.ingredientMappingKey, i]));
  const fromMap = byKey(from.ingredients);
  const toMap = byKey(to.ingredients);
  for (const [key, b] of toMap) {
    const a = fromMap.get(key);
    if (!a) {
      ingredientChanges.push({ action: "ADDED", from: null, to: snap(b) });
    } else {
      const field = fieldChanged(a, b);
      if (field) {
        ingredientChanges.push({
          action: "MODIFIED",
          from: snap(a),
          to: snap(b),
          fieldChanged: field,
        });
      }
    }
  }
  for (const [key, a] of fromMap) {
    if (!toMap.has(key)) {
      ingredientChanges.push({ action: "REMOVED", from: snap(a), to: null });
    }
  }

  const methodChanges: RecipeDiffDto["methodChanges"] = [];
  const maxStep = Math.max(from.methodSteps.length, to.methodSteps.length);
  for (let n = 1; n <= maxStep; n++) {
    const a = from.methodSteps.find((s) => s.stepNumber === n);
    const b = to.methodSteps.find((s) => s.stepNumber === n);
    if (a && b && a.instruction !== b.instruction) {
      methodChanges.push({
        action: "MODIFIED",
        step: n,
        from: a.instruction,
        to: b.instruction,
      });
    } else if (!a && b) {
      methodChanges.push({ action: "ADDED", step: n, from: null, to: b.instruction });
    } else if (a && !b) {
      methodChanges.push({ action: "REMOVED", step: n, from: a.instruction, to: null });
    }
  }

  const metadataChanges: RecipeDiffDto["metadataChanges"] = [];
  const ma = from.metadata;
  const mb = to.metadata;
  if (ma && mb) {
    const fields: Array<keyof NonNullable<RecipeVersionDto["metadata"]>> = [
      "servings",
      "prepTimeMins",
      "cookTimeMins",
      "totalTimeMins",
      "cuisine",
      "packable",
      "fridgeDays",
      "freezerWeeks",
    ];
    for (const f of fields) {
      const av = ma[f] ?? null;
      const bv = mb[f] ?? null;
      if (JSON.stringify(av) !== JSON.stringify(bv)) {
        metadataChanges.push({ action: "MODIFIED", field: String(f), from: av, to: bv });
      }
    }
  }

  const tagChanges: RecipeDiffDto["tagChanges"] = [];
  const ta = from.tags;
  const tb = to.tags;
  if (ta && tb) {
    const dims = ["protein", "cookingMethod", "complexity", "flavourProfile", "dietaryFlags"] as const;
    for (const d of dims) {
      const av = ta[d] ?? null;
      const bv = tb[d] ?? null;
      if (JSON.stringify(av) !== JSON.stringify(bv)) {
        tagChanges.push({ action: "MODIFIED", dimension: d, from: av, to: bv });
      }
    }
  }

  return {
    fromVersionId: from.id,
    toVersionId: to.id,
    ingredientChanges,
    methodChanges,
    metadataChanges,
    tagChanges,
  };
}

/* ---- with-substitutions overlay (#14) ------------------------------------------ */

/**
 * Computed projection of a version with its ACCEPTED substitutions applied:
 * overlay rows carry id = null (not persisted — the base version is never
 * mutated), method-overlay lines replace the named step's instruction, and
 * appliedSubstitutionIds drives the "n swaps applied" caption.
 */
export function versionWithSubstitutions(
  version: RecipeVersionDto,
  subs: RecipeSubstitutionDto[],
): RecipeVersionDto {
  const accepted = subs.filter(
    (s) => s.state === "ACCEPTED" && s.versionId === version.id,
  );
  if (accepted.length === 0) {
    return { ...version, appliedSubstitutionIds: [] };
  }
  const byOriginalKey = new Map(
    accepted.map((s) => [s.original.ingredientMappingKey, s]),
  );
  const ingredients: IngredientDto[] = version.ingredients.map((i) => {
    const sub = byOriginalKey.get(i.ingredientMappingKey);
    if (!sub) return i;
    return {
      ...i,
      id: null,
      ingredientMappingKey: sub.substitute.ingredientMappingKey,
      displayName: titleCase(sub.substitute.ingredientMappingKey),
      quantity: sub.substitute.quantity,
      unit: sub.substitute.unit,
    };
  });
  const overlayByStep = new Map<number, string>();
  for (const s of accepted) {
    for (const line of s.methodOverlay ?? []) {
      overlayByStep.set(line.step, line.instruction);
    }
  }
  const methodSteps: MethodStepDto[] = version.methodSteps.map((m) =>
    overlayByStep.has(m.stepNumber)
      ? { ...m, id: null, instruction: overlayByStep.get(m.stepNumber) as string }
      : m,
  );
  return {
    ...version,
    ingredients,
    methodSteps,
    appliedSubstitutionIds: accepted.map((s) => s.id),
  };
}

export function titleCase(key: string): string {
  return key.charAt(0).toUpperCase() + key.slice(1);
}

/* ---- ratings (#18–#23) ----------------------------------------------------------- */

/** Absent axes coalesce to taste in the aggregate (server rule, §7b). */
export function ratingAggregate(r: {
  taste: number;
  effortWorthIt?: number | null;
  portionFit?: number | null;
  repeatValue?: number | null;
}): number {
  const axes = [
    r.taste,
    r.effortWorthIt ?? r.taste,
    r.portionFit ?? r.taste,
    r.repeatValue ?? r.taste,
  ];
  return Math.round((axes.reduce((a, b) => a + b, 0) / axes.length) * 10) / 10;
}

const avg = (xs: number[]): number | null =>
  xs.length === 0 ? null : Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10;

/**
 * GET …/ratings/summary equivalent. versionId null → recipe-level aggregate
 * across all versions; avg* fields are null when count = 0.
 */
export function computeRatingSummary(
  rows: RecipeRatingDto[],
  versionId?: string | null,
): RecipeRatingSummaryDto {
  const scoped = versionId ? rows.filter((r) => r.versionId === versionId) : rows;
  const pick = (f: (r: RecipeRatingDto) => number | null | undefined): number[] =>
    scoped.map(f).filter((v): v is number => v != null);
  return {
    versionId: versionId ?? null,
    avgTaste: avg(pick((r) => r.taste)),
    avgEffortWorthIt: avg(pick((r) => r.effortWorthIt)),
    avgPortionFit: avg(pick((r) => r.portionFit)),
    avgRepeatValue: avg(pick((r) => r.repeatValue)),
    avgAggregate: avg(pick((r) => r.aggregate)),
    count: scoped.length,
  };
}

/* ---- misc reads ------------------------------------------------------------------ */

/** Count for the PARTIAL "n ingredients need review" badge. */
export function needsReviewCount(version: RecipeVersionDto | null | undefined): number {
  return version?.ingredients.filter((i) => i.needsReview).length ?? 0;
}

/** Deterministic hash for fake-but-stable computed values. */
export function hashCode(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0;
  return Math.abs(h);
}
